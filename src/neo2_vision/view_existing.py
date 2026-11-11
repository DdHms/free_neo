"""Display a fresh preview produced by an existing owner; never connects a drone."""
import argparse
import hashlib
from pathlib import Path
import re
import threading
import time
import uuid
import webbrowser
from neo2_vision.local_config import load_drone_config
from neo2_vision.preview import PreviewServer


def current_generation(root: Path):
    candidates = []
    for frame in root.glob('*/hls/preview.jpg'):
        generation = frame.parent.parent.name
        if not re.fullmatch(r'[0-9a-f]{24}', generation):
            continue
        try:
            timestamp = frame.stat().st_mtime
        except OSError:
            continue
        if 0 <= time.time() - timestamp <= 3:
            candidates.append((timestamp, generation))
    return max(candidates)[1] if candidates else None


def main():
    parser = argparse.ArgumentParser(description='View the existing local Neo 2 video session')
    parser.add_argument('--ble-id')
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    try:
        settings = load_drone_config()
        ble_id = args.ble_id or settings.ble_id
        if not ble_id:
            parser.error('set ble_id in neo2.local.json or pass --ble-id')
        address = str(uuid.UUID(ble_id)).upper()
    except (OSError, RuntimeError, ValueError) as error:
        parser.error(str(error))
    root = Path(__file__).resolve().parents[2] / 'private' / hashlib.sha256(address.encode()).hexdigest()[:12] / 'sessions'
    server = PreviewServer(root)
    stop = threading.Event()
    def refresh():
        while not stop.wait(.2):
            server.update({'connection': {'generation': current_generation(root)}})
    try:
        url = server.start()
        server.update({'connection': {'generation': current_generation(root)}})
        threading.Thread(target=refresh, name='neo2-preview-selection', daemon=True).start()
        print('Live view:', url, flush=True)
        if not args.no_browser:
            webbrowser.open(url)
        while not stop.wait(1):
            pass
    except KeyboardInterrupt:
        return 0
    finally:
        stop.set()
        server.close()


if __name__ == '__main__':
    raise SystemExit(main())
