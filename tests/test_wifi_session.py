import unittest
from ipaddress import IPv4Network
from neo2_vision.local_config import DroneConfig
from types import SimpleNamespace
from unittest.mock import patch
from neo2_vision.wifi_session import check_route, summarize


class SessionTests(unittest.TestCase):
    def test_summary_does_not_confuse_telemetry_with_flight_readiness(self):
        result = SimpleNamespace(transport_accepted=True, telemetry={
            "available": True, "flight_ready": False,
            "fields": {"is_flying": {"valid": True, "value": False},
                       "motors_on": {"valid": False, "value": True}}},
            wlm_mode_follow={"service_switch": {"committed": True}},
            video_bytes_delivered=10, reason="duration_elapsed")
        summary = summarize(result)
        self.assertTrue(summary["telemetry_received"])
        self.assertFalse(summary["flight_ready"])
        self.assertFalse(summary["is_flying"])
        self.assertIsNone(summary["motors_on"])

    def test_rejects_route_outside_drone_network(self):
        with patch("neo2_vision.wifi_session.socket.socket") as factory:
            factory.return_value.__enter__.return_value.getsockname.return_value = ("198.51.100.5", 1234)
            with self.assertRaises(RuntimeError):
                check_route(DroneConfig("192.0.2.1", 12345, IPv4Network("192.0.2.0/24"), "en0", None))
