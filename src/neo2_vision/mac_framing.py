"""Nonblocking bounded framing for a local macOS watchdog stream.

This is a transport primitive, not a controller or flight authorization.
The caller must poll it and its deadline from the independent owner loop.
"""
from collections import deque
import json
import socket
import struct
import time

MAX_FRAME = 32768
MAX_QUEUED = 4 * (MAX_FRAME + 4)


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def _constant(_):
    raise ValueError('nonfinite JSON value')


class FramedConnection:
    def __init__(self, sock: socket.socket, *, clock=time.monotonic, frame_timeout=.25):
        if frame_timeout <= 0:
            raise ValueError('frame timeout must be positive')
        self.sock, self.clock, self.frame_timeout = sock, clock, frame_timeout
        self.sock.setblocking(False)
        self.inbound = bytearray()
        self.outbound = bytearray()
        self.received = deque()
        self.read_deadline = self.write_deadline = None

    def queue(self, value):
        if not isinstance(value, dict):
            raise ValueError('message must be an object')
        body = json.dumps(value, allow_nan=False, separators=(',', ':')).encode('utf-8')
        if not 0 < len(body) <= MAX_FRAME or len(self.outbound) + len(body) + 4 > MAX_QUEUED:
            raise ValueError('outgoing frame exceeds limit')
        if not self.outbound:
            self.write_deadline = self.clock() + self.frame_timeout
        self.outbound.extend(struct.pack('!I', len(body)) + body)

    def read_available(self):
        try:
            chunk = self.sock.recv(MAX_FRAME + 4)
        except BlockingIOError:
            return
        if not chunk:
            raise EOFError('watchdog channel closed')
        if not self.inbound:
            self.read_deadline = self.clock() + self.frame_timeout
        self.inbound.extend(chunk)
        while len(self.inbound) >= 4:
            size = struct.unpack('!I', self.inbound[:4])[0]
            if not 0 < size <= MAX_FRAME:
                raise ValueError('invalid incoming frame size')
            if len(self.inbound) < 4 + size:
                break
            body = bytes(self.inbound[4:4+size])
            del self.inbound[:4+size]
            value = json.loads(body, object_pairs_hook=_object, parse_constant=_constant)
            if not isinstance(value, dict):
                raise ValueError('message must be an object')
            if len(self.received) >= 32:
                raise ValueError('too many queued messages')
            self.received.append(value)
            self.read_deadline = self.clock() + self.frame_timeout if self.inbound else None
        if len(self.inbound) > MAX_FRAME + 4:
            raise ValueError('incoming buffer exceeds limit')

    def flush_available(self):
        if not self.outbound:
            return
        try:
            written = self.sock.send(self.outbound)
        except BlockingIOError:
            return
        if written == 0:
            raise EOFError('watchdog channel closed')
        del self.outbound[:written]
        if not self.outbound:
            self.write_deadline = None

    def check_deadlines(self):
        now = self.clock()
        if (self.read_deadline is not None and now >= self.read_deadline or
                self.write_deadline is not None and now >= self.write_deadline):
            raise TimeoutError('watchdog channel frame timed out')
