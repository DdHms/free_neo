import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

try:
    from neo2_vision.mac_incident import MacFlightIncidentStore
except ModuleNotFoundError:
    MacFlightIncidentStore = None

ADDRESS = '12345678-1234-4ABC-8DEF-123456789ABC'


@unittest.skipIf(MacFlightIncidentStore is None, 'optional neo2-control dependency is not installed')
class IncidentTests(unittest.TestCase):
    def test_survives_owner_crash_and_requires_matching_peer(self):
        with tempfile.TemporaryDirectory() as directory:
            code = """import os,sys
from neo2_vision.mac_incident import MacFlightIncidentStore
with MacFlightIncidentStore(sys.argv[1],sys.argv[2]) as store:
    store.mark_airborne('a'*64, 42)
    os._exit(23)
"""
            child = subprocess.run([sys.executable, '-c', code, directory, ADDRESS], env=os.environ.copy())
            self.assertEqual(child.returncode, 23)
            with MacFlightIncidentStore(directory, ADDRESS.lower()) as store:
                self.assertEqual(store.active['peer_hash'], 42)
                with self.assertRaises(ValueError):
                    store.resolve_ground('b' * 64, 42)
                self.assertIsNotNone(store.active)
                store.resolve_ground('a' * 64, 42)
            with MacFlightIncidentStore(directory, ADDRESS) as store:
                self.assertIsNone(store.active)

    def test_invalid_uuid_is_rejected(self):
        with self.assertRaises(ValueError):
            MacFlightIncidentStore('/tmp', 'invalid')
