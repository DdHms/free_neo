"""Load per-installation drone connection settings from the ignored local file."""
from dataclasses import dataclass
from ipaddress import IPv4Address, IPv4Network
import json
from pathlib import Path
import re
import uuid


CONFIG_PATH = Path(__file__).resolve().parents[2] / 'neo2.local.json'


@dataclass(frozen=True)
class DroneConfig:
    host: str
    port: int
    network: IPv4Network
    interface: str
    ble_id: str | None

    def accepts_source(self, address: str) -> bool:
        return address != self.host and IPv4Address(address) in self.network


def load_drone_config() -> DroneConfig:
    try:
        values = json.loads(CONFIG_PATH.read_text(encoding='utf-8'))
    except FileNotFoundError:
        raise RuntimeError('Create neo2.local.json from neo2.example.json before connecting') from None
    except (ValueError, UnicodeError):
        raise ValueError('Invalid JSON in neo2.local.json') from None
    try:
        host = IPv4Address(values['host'])
        network = IPv4Network(values['network'])
        port = values['port']
        interface = values['interface']
        ble_id = values.get('ble_id')
        if (host not in network or type(port) is not int or not 1 <= port <= 65535
                or not re.fullmatch(r'[A-Za-z0-9_.-]{1,15}', interface)):
            raise ValueError
        return DroneConfig(str(host), port, network, interface,
                           str(uuid.UUID(ble_id)).upper() if ble_id else None)
    except (AttributeError, KeyError, TypeError, ValueError):
        raise ValueError('Invalid host, network, port, interface, or BLE ID in neo2.local.json') from None
