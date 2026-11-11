"""macOS Wi-Fi route and socket binding for Neo 2 sessions."""
import socket
import sys

from neo2_vision.local_config import load_drone_config

# IP_BOUND_IF from macOS SDK <netinet/in.h>; CPython does not expose it.
IP_BOUND_IF = 25


def bind_drone_socket(sock: socket.socket, interface: str) -> str:
    """Bind a UDP socket to a macOS interface and check its selected source.

    Connecting a UDP socket selects its route but sends no packets. The returned
    source address is valid only for this socket's current connection; callers
    must handle disconnects and must never infer flight readiness from it.
    """
    if sys.platform != "darwin":
        raise RuntimeError("macOS is required for IP_BOUND_IF")
    if not isinstance(interface, str) or not interface or len(interface) > 15:
        raise ValueError("Invalid network interface")
    config = load_drone_config()
    index = socket.if_nametoindex(interface)
    sock.setsockopt(socket.IPPROTO_IP, IP_BOUND_IF, index)
    sock.connect((config.host, config.port))
    source = sock.getsockname()[0]
    if not config.accepts_source(source):
        raise RuntimeError("The selected interface is not connected to the drone network")
    return str(source)
