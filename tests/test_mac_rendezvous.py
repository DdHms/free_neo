import os
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from neo2_vision.mac_rendezvous import RecoveryListener, connect_recovery

ADDRESS = '12345678-1234-4ABC-8DEF-123456789ABC'


@unittest.skipUnless(__import__('sys').platform == 'darwin', 'requires macOS')
class RecoveryTests(unittest.TestCase):
    def test_same_user_connection_and_live_socket_not_replaced(self):
        with tempfile.TemporaryDirectory(dir='/private/tmp') as d:
            with RecoveryListener(d, ADDRESS) as listener:
                with self.assertRaises(OSError):
                    with RecoveryListener(d, ADDRESS):
                        pass
                client, owner = connect_recovery(d, ADDRESS)
                server, peer = listener.accept()
                try:
                    self.assertEqual(owner[0], os.getpid())
                    self.assertEqual(peer[1], os.geteuid())
                    client.sendall(b'hello')
                    self.assertEqual(server.recv(5), b'hello')
                finally:
                    client.close(); server.close()
            self.assertFalse(listener.path.exists())

    def test_rejects_nonprivate_directory_and_unrelated_file(self):
        with tempfile.TemporaryDirectory(dir='/private/tmp') as d:
            os.chmod(d, 0o755)
            with self.assertRaises(RuntimeError):
                RecoveryListener(d, ADDRESS).__enter__()
            os.chmod(d, 0o700)
            listener = RecoveryListener(d, ADDRESS)
            listener.path.write_text('keep')
            with self.assertRaises(OSError):
                listener.__enter__()
            self.assertEqual(listener.path.read_text(), 'keep')
