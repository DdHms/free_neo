import asyncio
import unittest
from unittest.mock import patch

try:
    from neo2_vision.mac_flight_connection import MacFlightConfig, MacDeviceLease, ExistingMacWifiLink, check_drone_interface
except ModuleNotFoundError:
    MacFlightConfig = None


@unittest.skipIf(MacFlightConfig is None, 'optional neo2-control dependency is not installed')
class MacFlightConnectionTests(unittest.TestCase):
    def test_config_accepts_macos_uuid_and_preserves_reference_limits(self):
        params = dict(interface='en0', wlm_identity_file='/tmp/wlm', ble_identity_file='/tmp/ble',
                      ble_address='12345678-1234-4ABC-8DEF-123456789ABC')
        config = MacFlightConfig(**params, vertical_trial=True)
        self.assertEqual(config.ble_address, params['ble_address'])
        with self.assertRaises(ValueError):
            MacFlightConfig(**{**params, 'ble_address': 'invalid'})
        with self.assertRaises(ValueError):
            MacFlightConfig(**params, vertical_trial=True, yaw_trial=True)

    def test_lease_is_stable_and_scoped_to_drone_and_interface(self):
        address = '12345678-1234-4ABC-8DEF-123456789ABC'
        self.assertEqual(MacDeviceLease('en0', address).keys, MacDeviceLease('en0', address.lower()).keys)
        self.assertNotEqual(MacDeviceLease('en0', address).keys, MacDeviceLease('en1', address).keys)

    def test_wrong_wifi_reports_actionable_error(self):
        with patch('neo2_vision.mac_flight_connection.bind_drone_socket', side_effect=RuntimeError('wrong network')):
            with self.assertRaisesRegex(RuntimeError, 'drone_wifi_not_connected'):
                check_drone_interface('en0')

    def test_existing_wifi_is_checked_and_not_modified(self):
        async def check():
            with patch('neo2_vision.mac_flight_connection.check_drone_interface') as route:
                link = ExistingMacWifiLink(None, 'en0', stop_event=None)
                async with link:
                    self.assertFalse(link.snapshot()['cleanup_verified'])
                self.assertTrue(link.snapshot()['cleanup_verified'])
                self.assertFalse(link.snapshot()['association_owned'])
                route.assert_called_once_with('en0', cancel_event=None)
        asyncio.run(check())
