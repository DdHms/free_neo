import socket
import struct
import unittest
from neo2_vision.mac_framing import FramedConnection, MAX_FRAME


class FramingTests(unittest.TestCase):
    def setUp(self):
        self.a, self.b = socket.socketpair()
        self.now = 0.0
        self.connection = FramedConnection(self.a, clock=lambda: self.now)

    def tearDown(self):
        self.a.close(); self.b.close()

    def test_fragmented_and_coalesced_frames(self):
        wire = struct.pack('!I', 7) + b'{"a":1}' + struct.pack('!I', 7) + b'{"b":2}'
        self.b.sendall(wire[:2]); self.connection.read_available()
        self.assertEqual(len(self.connection.received), 0)
        self.b.sendall(wire[2:]); self.connection.read_available()
        self.assertEqual(list(self.connection.received), [{'a': 1}, {'b': 2}])
        self.assertIsNone(self.connection.read_deadline)

    def test_partial_frame_deadline_is_not_renewed_by_more_bytes(self):
        self.b.sendall(b'\x00'); self.connection.read_available()
        self.now = .2
        self.b.sendall(b'\x00'); self.connection.read_available()
        self.now = .25
        with self.assertRaises(TimeoutError): self.connection.check_deadlines()

    def test_rejects_oversized_and_duplicate_fields(self):
        self.b.sendall(struct.pack('!I', MAX_FRAME+1))
        with self.assertRaises(ValueError): self.connection.read_available()
        self.connection.inbound.clear(); self.connection.read_deadline = None
        body = b'{"a":1,"a":2}'
        self.b.sendall(struct.pack('!I', len(body)) + body)
        with self.assertRaises(ValueError): self.connection.read_available()

    def test_output_roundtrip(self):
        self.connection.queue({'hello': 'world'})
        self.connection.flush_available()
        size = struct.unpack('!I', self.b.recv(4))[0]
        self.assertEqual(self.b.recv(size), b'{"hello":"world"}')
        self.assertIsNone(self.connection.write_deadline)
