"""Video-only session. Holds the aircraft lock and never creates a flight channel."""
import argparse
import hashlib
from pathlib import Path
import secrets
import sys
import webbrowser
import uuid
from neo2_vision.mac_flight_connection import MacDeviceLease
from neo2_vision.mac_incident import MacFlightIncidentStore
from neo2_vision.local_config import load_drone_config
from neo2_vision.preview import PreviewServer
from neo2_vision.vendor.macos_session import run_session
from neo2_control.camera import CameraChannel


def main():
    parser = argparse.ArgumentParser(description='Start a video-only Neo 2 session')
    parser.add_argument('--ble-id')
    parser.add_argument('--interface')
    parser.add_argument('--duration', type=int, default=300)
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    if not 10 <= args.duration <= 600:
        parser.error('duration must be between 10 and 600 seconds')
    try:
        settings = load_drone_config()
        ble_id = args.ble_id or settings.ble_id
        if not ble_id:
            parser.error('set ble_id in neo2.local.json or pass --ble-id')
        address = str(uuid.UUID(ble_id)).upper()
    except (OSError, RuntimeError, ValueError) as error:
        parser.error(str(error))
    interface = args.interface or settings.interface
    state = Path(__file__).resolve().parents[2] / 'private' / hashlib.sha256(address.encode()).hexdigest()[:12]
    generation = secrets.token_hex(12)
    session_dir = state / 'sessions' / generation
    preview = PreviewServer(state / 'sessions')
    try:
        # Both locks prevent a flight owner from using this drone concurrently.
        with MacDeviceLease(interface, address), MacFlightIncidentStore(state / 'incidents', address):
            session_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
            url = preview.start()
            preview.update({'connection': {'generation': generation}})
            print('Live view:', url, flush=True)
            if not args.no_browser:
                webbrowser.open(url)
            result = run_session(capture_path=session_dir / 'capture.jsonl', interface=interface,
                duration=args.duration, heartbeat=True, wlm_identity_file=state / 'wlm.json',
                wlm_wifi_liveview=True, camera_channel=CameraChannel(),
                hls_directory=session_dir / 'hls', preview_jpeg=True, video_codec='hevc')
            print('Video session:', result.reason, flush=True)
            return 0 if result.transport_accepted else 1
    except KeyboardInterrupt:
        return 0
    except Exception as error:
        print('Video session failed:', error, file=sys.stderr, flush=True)
        return 1
    finally:
        preview.close()


if __name__ == '__main__':
    raise SystemExit(main())
