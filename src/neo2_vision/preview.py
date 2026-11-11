"""Private, loopback-only JPEG preview for the supervised session."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import secrets
import stat
import threading
import time
from urllib.parse import urlsplit

MAX_JPEG = 4 * 1024 * 1024
MAX_AGE = 3.0


class PreviewServer:
    def __init__(self, capture_directory):
        self.root = Path(capture_directory)
        self.token = secrets.token_urlsafe(24)
        self.generation = None
        self.lock = threading.Lock()
        self.server = None
        self.thread = None

    def update(self, status):
        generation = (status.get('connection') or {}).get('generation')
        with self.lock:
            self.generation = generation if isinstance(generation, str) and re.fullmatch(r'[0-9a-f]{24}', generation) else None

    def frame(self):
        with self.lock:
            generation = self.generation
        if generation is None:
            raise FileNotFoundError
        path = self.root / generation / 'hls' / 'preview.jpg'
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        with os.fdopen(fd, 'rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_JPEG:
                raise FileNotFoundError
            data = stream.read(MAX_JPEG + 1)
            after = os.fstat(stream.fileno())
        age = time.time() - before.st_mtime
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or not 0 <= age <= MAX_AGE or len(data) > MAX_JPEG:
            raise FileNotFoundError
        return data

    def start(self):
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                path = urlsplit(self.path).path
                if path == '/' + owner.token + '/':
                    body = ("<!doctype html><meta charset='utf-8'><title>Neo 2 live view</title>"
                            "<style>body{background:#111;color:#eee;font:16px sans-serif;text-align:center}"
                            "img{max-width:95vw;max-height:85vh}</style><h2>Neo 2 live view</h2>"
                            "<p>Preview may be delayed. Keep the aircraft in sight.</p>"
                            "<img id='frame' alt='Waiting for video'>"
                            "<script>const f=document.getElementById('frame');"
                            "setInterval(()=>{f.src='frame?t='+Date.now()},250)</script>").encode()
                    self.reply(200, body, 'text/html; charset=utf-8')
                elif path == '/' + owner.token + '/frame':
                    try:
                        body = owner.frame()
                    except (OSError, ValueError):
                        self.reply(503, b'Waiting for video', 'text/plain')
                    else:
                        self.reply(200, body, 'image/jpeg')
                else:
                    self.reply(404, b'Not found', 'text/plain')

            def reply(self, code, body, kind):
                self.send_response(code)
                self.send_header('Content-Type', kind)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Security-Policy', "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'")
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.end_headers()
                try: self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError): pass

            def log_message(self, *_):
                pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, name='neo2-preview', daemon=True)
        self.thread.start()
        return f'http://127.0.0.1:{self.server.server_port}/{self.token}/'

    def close(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=2)
            self.server = None
