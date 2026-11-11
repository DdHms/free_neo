"""macOS connection adapters for the reference flight supervisor.

This module does not start a flight or grant flight readiness. The network must
already be associated with the drone. It preserves the upstream connection
owner, BLE retention, session, and supervisor safeguards.
"""
import asyncio
from dataclasses import asdict, dataclass
import hashlib
import socket
import uuid

from neo2_control.device_lease import DeviceLease
from neo2_control.flight_connection import FlightConfig, FlightConnection, OwnedFlightBackend
from neo2_vision.macos_network import bind_drone_socket
from neo2_vision.vendor.macos_ble import run_ble_wifi_setup
from neo2_vision.vendor.macos_session import run_session


@dataclass(frozen=True)
class MacFlightConfig(FlightConfig):
    def __post_init__(self):
        try:
            parsed = uuid.UUID(self.ble_address)
            if str(parsed) != self.ble_address.lower():
                raise ValueError
        except (ValueError, TypeError, AttributeError):
            raise ValueError('invalid_macos_ble_identifier') from None
        # Validate all other upstream constraints without changing this object.
        FlightConfig(**{**asdict(self), 'ble_address': '02:00:00:00:00:01'})


def aircraft_identity(address):
    return hashlib.sha256(b'neo2-macos-ble-v1\0' + uuid.UUID(address).bytes).hexdigest()


class MacDeviceLease(DeviceLease):
    def __init__(self, interface, address):
        identifier = uuid.UUID(address)
        # The upstream lock hashes its input; this stable pseudonym gives it a
        # valid address without revealing the platform-specific BLE identifier.
        digest = hashlib.sha256(identifier.bytes).digest()[:6]
        super().__init__(interface, ':'.join(f'{byte:02X}' for byte in digest))


def check_drone_interface(interface, *, cancel_event=None):
    if cancel_event is not None and cancel_event.is_set():
        raise asyncio.CancelledError
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            bind_drone_socket(sock, interface)
    except (OSError, ValueError, RuntimeError):
        # The upstream owner exposes only machine-readable, non-sensitive codes.
        raise RuntimeError('drone_wifi_not_connected') from None


class ExistingMacWifiLink:
    """Retain an already-associated Wi-Fi link; never change system networks."""
    def __init__(self, credentials, interface, *, stop_event):
        self.interface = interface
        self.stop_event = stop_event
        self.cleaned = False

    async def __aenter__(self):
        check_drone_interface(self.interface, cancel_event=self.stop_event)
        return self

    async def __aexit__(self, *_):
        # This owner did not create or change the existing Wi-Fi association.
        self.cleaned = True

    def snapshot(self):
        return {'cleanup_verified': self.cleaned, 'association_owned': False}


def run_mac_session(**kwargs):
    # The preview pipeline already consumes video in bounded queues; do not
    # create a second, unbounded raw video file during an operator session.
    kwargs.pop('video_path', None)
    return run_session(**kwargs)


class MacFlightConnection(FlightConnection):
    def __init__(self, config, channel, **kwargs):
        super().__init__(config, channel, session_runner=run_mac_session,
                         ble_runner=run_ble_wifi_setup, wifi_factory=ExistingMacWifiLink,
                         route_guard=check_drone_interface, lease_factory=MacDeviceLease,
                         ble_release=lambda _address: None, **kwargs)


class MacFlightBackend(OwnedFlightBackend):
    def __init__(self, config, **kwargs):
        if not isinstance(config, MacFlightConfig):
            raise ValueError('mac_flight_config_required')
        super().__init__(config, connection_factory=MacFlightConnection, **kwargs)

    def connection_status(self):
        return {**super().connection_status(), 'mac_aircraft_identity': aircraft_identity(self.config.ble_address)}
