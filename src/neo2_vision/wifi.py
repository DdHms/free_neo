"""Read-only discovery of possible Neo 2 Wi-Fi access points on macOS."""
import json
import subprocess
import sys


def candidates(report: dict) -> list[dict[str, str]]:
    result = []
    for device in report.get("SPAirPortDataType", []):
        for interface in device.get("spairport_airport_interfaces", []):
            networks = list(interface.get("spairport_airport_other_local_wireless_networks", []))
            current = interface.get("spairport_current_network_information")
            if isinstance(current, dict):
                networks.append(current)
            for network in networks:
                name = network.get("_name", "")
                if name.upper().startswith("DJI-NEO2-"):
                    result.append({"ssid": name, "interface": interface.get("_name", ""),
                                   "channel": network.get("spairport_network_channel", "")})
    return result


def main() -> int:
    if sys.platform != "darwin":
        print("Wi-Fi discovery currently supports macOS only", file=sys.stderr)
        return 1
    try:
        response = subprocess.run(["/usr/sbin/system_profiler", "SPAirPortDataType", "-json"],
                                  capture_output=True, text=True, timeout=30, check=True)
        print(json.dumps(candidates(json.loads(response.stdout)), indent=2))
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        print(f"Wi-Fi discovery failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
