import unittest
from unittest.mock import Mock, patch
from neo2_vision.macos_network import bind_drone_socket, IP_BOUND_IF
import socket
from ipaddress import IPv4Network
from neo2_vision.local_config import DroneConfig


class MacNetworkTests(unittest.TestCase):
    def setUp(self):
        config = DroneConfig("192.0.2.1", 12345, IPv4Network("192.0.2.0/24"), "en0", None)
        self.enterContext(patch("neo2_vision.macos_network.load_drone_config", return_value=config))

    def test_binds_interface_and_checks_source(self):
        sock = Mock()
        sock.getsockname.return_value = ("192.0.2.132", 50000)
        with patch('neo2_vision.macos_network.sys.platform', 'darwin'), patch('neo2_vision.macos_network.socket.if_nametoindex', return_value=14):
            self.assertEqual(bind_drone_socket(sock, 'en0'), '192.0.2.132')
        sock.setsockopt.assert_called_once_with(socket.IPPROTO_IP, IP_BOUND_IF, 14)
        sock.connect.assert_called_once_with(('192.0.2.1', 12345))

    def test_rejects_wrong_subnet(self):
        sock = Mock()
        sock.getsockname.return_value = ('198.51.100.2', 50000)
        with patch('neo2_vision.macos_network.sys.platform', 'darwin'), patch('neo2_vision.macos_network.socket.if_nametoindex', return_value=14):
            with self.assertRaises(RuntimeError):
                bind_drone_socket(sock, 'en0')
