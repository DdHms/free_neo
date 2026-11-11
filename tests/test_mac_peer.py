import os
import socket
import sys
import unittest
from neo2_vision.mac_peer import peer_identity


@unittest.skipUnless(sys.platform == 'darwin', 'requires macOS')
class MacPeerTests(unittest.TestCase):
    def test_peer_credentials_for_socketpair(self):
        a, b = socket.socketpair()
        try:
            self.assertEqual(peer_identity(a), (os.getpid(), os.geteuid(), os.getegid()))
        finally:
            a.close()
            b.close()
