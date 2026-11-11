import tempfile
import unittest
from pathlib import Path

try:
    import neo2_control.control_owner
except ModuleNotFoundError:
    neo2_control = None
else:
    neo2_control = True


@unittest.skipIf(neo2_control is None, 'optional neo2-control dependency is not installed')
class WatchdogSimulationTests(unittest.TestCase):
    def test_parent_timeout_lands_synthetic_aircraft(self):
        from neo2_vision.mac_watchdog_sim import run_simulation
        with tempfile.TemporaryDirectory() as directory:
            result = run_simulation(Path(directory) / 'journal.jsonl')
        self.assertTrue(result['synthetic'])
        self.assertEqual(result['parent_timeout'], 'controller_parent_timeout')
        self.assertTrue(result['landing_confirmed'])
        self.assertFalse(result['possible_airborne'])

    def test_stream_framing_handles_fragmentation_and_coalescing(self):
        import socket
        import struct
        from neo2_vision.mac_watchdog_sim import _receive
        a, b = socket.socketpair()
        try:
            one, two = b'{"id":1}', b'{"id":2}'
            wire = struct.pack('!I', len(one)) + one + struct.pack('!I', len(two)) + two
            b.sendall(wire[:3])
            b.sendall(wire[3:])
            self.assertEqual(_receive(a), {'id': 1})
            self.assertEqual(_receive(a), {'id': 2})
        finally:
            a.close()
            b.close()
