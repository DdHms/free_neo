"""Persistent aircraft-uncertainty records keyed by a macOS BLE UUID."""
import uuid
from pathlib import Path
from neo2_control.flight_incident import FlightIncidentStore
from neo2_vision.mac_flight_connection import aircraft_identity


class MacFlightIncidentStore(FlightIncidentStore):
    """Reuse the reference store's locking and durability for macOS identity."""
    def __init__(self, directory, address):
        try:
            identity = uuid.UUID(address)
            if str(identity) != address.lower():
                raise ValueError
        except (TypeError, ValueError, AttributeError):
            raise ValueError('invalid_macos_ble_identifier') from None
        # macOS commonly exposes /var and /tmp as system symlinks. Resolve
        # them before the upstream no-follow directory walk.
        super().__init__(Path(directory).resolve(), '02:00:00:00:00:01')
        self.key = aircraft_identity(str(identity))
        self.filename = self.key + '.json'
