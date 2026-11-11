"""Read-only BLE scan. Advertised names are hints, not device identification."""
import argparse
import asyncio
import json
import sys


DJI_SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"


async def probe(address: str, timeout: float) -> dict[str, object]:
    """Connect over BLE and enumerate services without writing application data."""
    from bleak import BleakClient

    async with asyncio.timeout(timeout):
        async with BleakClient(address, timeout=timeout, pair=False) as client:
            return {"connected": client.is_connected, "services": [
                {"uuid": service.uuid, "characteristics": [
                    {"uuid": char.uuid, "properties": list(char.properties)}
                    for char in service.characteristics]}
                for service in client.services]}


async def scan(timeout: float) -> list[dict[str, object]]:
    from bleak import BleakScanner

    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    return [
        {"name": advertisement.local_name or device.name,
         "address": device.address,
         "rssi": advertisement.rssi,
         "service_uuids": list(advertisement.service_uuids),
         "candidate": DJI_SERVICE in [u.lower() for u in advertisement.service_uuids] or
                      any(term in (advertisement.local_name or device.name or "").upper() for term in ("DJI", "NEO"))}
        for device, advertisement in devices.values()
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan nearby Bluetooth devices without connecting")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--probe", metavar="ADDRESS", help="connect to a discovered device and list its BLE services")
    args = parser.parse_args()
    if not 0 < args.timeout <= 120:
        parser.error("timeout must be greater than zero and at most 120 seconds")
    try:
        results = asyncio.run(probe(args.probe, args.timeout) if args.probe else scan(args.timeout))
    except Exception as exc:
        print(f"Bluetooth scan failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(results, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
