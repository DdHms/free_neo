"""Observe a Neo 2 Wi-Fi session without sending flight commands."""
import argparse
import json
from pathlib import Path
import socket
import sys
import tempfile

from neo2_vision.local_config import DroneConfig, load_drone_config


def check_route(config: DroneConfig | None = None) -> None:
    config = load_drone_config() if config is None else config
    # A UDP connect selects a route without sending a datagram.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect((config.host, config.port))
        source = sock.getsockname()[0]
    if not config.accepts_source(source):
        raise RuntimeError("Connect Wi-Fi to the drone before starting a session")


def summarize(result) -> dict:
    fields = result.telemetry.get("fields", {})
    def value(name):
        field = fields.get(name, {})
        return field.get("value") if field.get("valid") else None
    link = result.wlm_mode_follow
    return {
        "transport_accepted": result.transport_accepted,
        "service_switch_completed": link.get("service_switch", {}).get("committed", False),
        "telemetry_received": result.telemetry.get("available", False),
        "is_flying": value("is_flying"),
        "motors_on": value("motors_on"),
        "video_bytes_received": result.video_bytes_delivered,
        "flight_ready": result.telemetry.get("flight_ready", False),
        "session_reason": result.reason,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Observe drone Wi-Fi telemetry without flight commands")
    parser.add_argument("--duration", type=int, default=15)
    parser.add_argument("--preflight", action="store_true", help="inspect live flight-observer readiness without sending commands")
    args = parser.parse_args()
    if not 5 <= args.duration <= 120:
        parser.error("duration must be between 5 and 120 seconds")
    try:
        from neo2_vision.vendor.macos_session import run_session
        config = load_drone_config()
        check_route(config)
        with tempfile.TemporaryDirectory(prefix="neo2-observe-") as directory:
            root = Path(directory)
            extra = {}
            observation = {}
            if args.preflight:
                from neo2_control.flight_channel import FlightChannel
                channel = FlightChannel(vertical_trial=True)
                channel.enable_observations()
                def capture(snapshot):
                    if snapshot.get("state") != "open":
                        return
                    sample = channel.observation()
                    state = channel.observer.snapshot()
                    observation.update({"problems": state.get("problems", []),
                                        "control_ready": None if sample is None else sample.control_ready,
                                        "flying": None if sample is None else sample.flying,
                                        "motors_on": None if sample is None else sample.motors_on,
                                        "battery_percent": None if sample is None else sample.battery_percent})
                extra = {"flight_channel": channel, "snapshot_sink": capture}
            result = run_session(capture_path=root / "capture.jsonl", duration=args.duration,
                                 heartbeat=True, wlm_identity_file=root / "identity.json",
                                 wlm_wifi_liveview=True, interface=config.interface, **extra)
            summary = summarize(result)
            if args.preflight:
                summary["preflight_observation"] = observation
                summary["flight_commands_sent"] = channel.sent_count
        print(json.dumps(summary, indent=2))
        return 0 if summary["transport_accepted"] and summary["service_switch_completed"] else 1
    except ImportError:
        print("Install the optional session dependency: pip install -e '.[session]'", file=sys.stderr)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Session failed: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
