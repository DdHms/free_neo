import unittest
from neo2_vision.wifi import candidates


class WifiTests(unittest.TestCase):
    def test_only_neo2_candidates_across_interfaces(self):
        report = {"SPAirPortDataType": [{"spairport_airport_interfaces": [
            {"_name": "en0", "spairport_airport_other_local_wireless_networks": [
                {"_name": "DJI-NEO2-test", "spairport_network_channel": "44"},
                {"_name": "DJI-NEO-test"}, {"_name": "home"}]},
            {"_name": "en1", "spairport_current_network_information":
                {"_name": "dji-neo2-second"}}]}]}
        self.assertEqual(candidates(report), [
            {"ssid": "DJI-NEO2-test", "interface": "en0", "channel": "44"},
            {"ssid": "dji-neo2-second", "interface": "en1", "channel": ""}])

    def test_empty_report(self):
        self.assertEqual(candidates({}), [])
