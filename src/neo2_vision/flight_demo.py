"""Explicitly supervised Neo 2 vertical demo; an operator must be present.

This is experimental and requires a clear area and an independently verified
way to intervene. Command ACK is not landing confirmation. The reference
client has documented a case where an acknowledged landing did not descend.
"""
import argparse
import asyncio
import hashlib
import math
import os
from pathlib import Path
import secrets
import sys
import time
import uuid
import webbrowser

from neo2_vision.discovery import scan
from neo2_vision.local_config import load_drone_config


def identify_device(devices):
    candidates = [d for d in devices if d.get('candidate') and
                  str(d.get('name') or '').upper().startswith('DJI-NEO2-')]
    if len(candidates) != 1:
        raise RuntimeError('Expected exactly one Neo 2 Bluetooth candidate; found ' + str(len(candidates)))
    return str(candidates[0]['address'])


def readiness_problem(status):
    sample = status.get('telemetry') or {}
    limits = status.get('limits') or {}
    battery = sample.get('battery_percent')
    minimum = limits.get('minimum_battery_percent')
    if type(battery) not in (int, float) or not math.isfinite(battery):
        return 'battery level unavailable'
    if type(minimum) not in (int, float) or not math.isfinite(minimum):
        return 'minimum battery threshold unavailable'
    if battery < minimum:
        return f'battery is {battery:g}%; at least {minimum:g}% is required'
    if sample.get('control_ready') is not True:
        return 'control telemetry is unavailable or stale'
    if sample.get('flying') is not False or sample.get('motors_on') is not False:
        return 'the drone is not confirmed stopped'
    if sample.get('altitude_m') is None:
        return 'altitude telemetry is unavailable'
    return None


def can_retry_landing(status):
    """Retry only an acknowledged landing with fresh evidence of flight."""
    sample = status.get('telemetry') or {}
    ages = sample.get('component_age_seconds') or {}
    freshness = (status.get('limits') or {}).get('telemetry_age')
    if type(freshness) not in (int, float) or not math.isfinite(freshness) or freshness <= 0:
        return False
    freshness = min(freshness, .5)
    if any(type(ages.get(key)) not in (int, float) or not math.isfinite(ages[key])
           or not 0 <= ages[key] <= freshness for key in ('motion', 'control')):
        return False
    return (status.get('connected') is True and status.get('possible_airborne') is True
            and status.get('state') in ('landing', 'fault')
            and status.get('land_acknowledged') is True
            and 'pending_command_sequence' in status and status['pending_command_sequence'] is None
            and sample.get('control_ready') is True and sample.get('flying') is True
            and sample.get('motors_on') is True
            and (status.get('landing_confirmation') or {}).get('available') is not True)


def run_demo(runtime, *, hover_seconds=3, read_input=input, clock=time.monotonic, sleep=time.sleep, preview=None, video_enabled=True):
    """Run one supervised vertical cycle. Never retry an uncertain takeoff."""
    if not 1 <= hover_seconds <= 5:
        raise ValueError('hover duration must be between 1 and 5 seconds')
    if type(video_enabled) is not bool:
        raise ValueError('video_enabled must be boolean')
    attempted = False
    land_requested = False
    lease = None
    try:
        initial = runtime.status()
        if not getattr(runtime, 'adopted', False) or initial.get('state') == 'disconnected':
            runtime.handle('open')
        elif initial.get('state') not in ('not_ready', 'ready', 'fault'):
            raise RuntimeError('retained controller is not in a safe ground-recovery state')
        deadline = clock() + 35
        wifi_reported = False
        connection = {}
        phase = None
        print('Connecting; if the drone requests confirmation, hold its power button.', flush=True)
        while clock() < deadline:
            status = runtime.status()
            if preview is not None: preview.update(status)
            connection = status.get('connection', {})
            phase = connection.get('phase')
            if phase == 'session_open' and not wifi_reported:
                print('Wi-Fi session open. Waiting for flight control readiness.', flush=True)
                wifi_reported = True
            if (status.get('connected') and (status.get('telemetry') or {}).get('control_ready')
                    and (not video_enabled or (connection.get('video') or {}).get('live') is True)):
                break
            if phase in ('failed', 'closed'):
                code = connection.get('error')
                if code == 'drone_wifi_not_connected':
                    raise RuntimeError('connect this Mac’s Wi-Fi to the drone, then retry')
                raise RuntimeError('connection failed: ' + str(code))
            sleep(.1)
        else:
            if (connection.get('control_setup') or {}).get('state') == 'failed':
                raise RuntimeError('Wi-Fi connected, but flight control setup failed')
            if phase == 'ble_connecting':
                raise RuntimeError('timed out during Bluetooth setup')
            if video_enabled and (status.get('telemetry') or {}).get('control_ready'):
                raise RuntimeError('timed out waiting for live video')
            raise RuntimeError('timed out waiting for a control-ready connection')
        if not video_enabled:
            print('Video is off. Keep the drone in direct sight and the independent override ready.', flush=True)
        # Flight-state and video streams can start before the slower battery
        # stream. Wait briefly for missing data without relaxing any limits.
        deadline = clock() + 5
        problem = readiness_problem(status)
        while problem in ('battery level unavailable', 'control telemetry is unavailable or stale') and clock() < deadline:
            sleep(.1)
            status = runtime.status()
            if preview is not None: preview.update(status)
            problem = readiness_problem(status)
        if problem:
            raise RuntimeError('not ready: ' + problem)
        print('The next action may start the propellers and lift the drone.', flush=True)
        if read_input('Type READY only if the drone is on the ground, the area is clear, and an independent override is ready: ').strip() != 'READY':
            raise RuntimeError('operator readiness not confirmed')
        status = runtime.status()
        if preview is not None: preview.update(status)
        if video_enabled and (status.get('connection', {}).get('video') or {}).get('live') is not True:
            raise RuntimeError('live preview is unavailable or stale')
        token = runtime.handle('prepare', {'operation': 'ground'})['confirmation_token']
        runtime.handle('confirm_ground', {'confirmation_token': token})
        status = runtime.status()
        if status.get('state') == 'fault':
            if (status.get('fault_reason') != 'unresolved_previous_flight'
                    or (status.get('recovery') or {}).get('required') is not False
                    or status.get('possible_airborne') is not False):
                raise RuntimeError('controller fault remains unresolved: ' + str(status.get('fault_reason')))
            runtime.handle('reset')
            status = runtime.status()
        deadline = clock() + 1
        while status.get('state') == 'not_ready' and clock() < deadline:
            sleep(.1)
            status = runtime.status()
        if status.get('state') != 'ready':
            detail = readiness_problem(status) or 'required fresh ground or safety telemetry is unavailable'
            raise RuntimeError('controller is not ready after ground confirmation: ' + detail)
        token = runtime.handle('prepare', {'operation': 'arm'})['confirmation_token']
        lease = runtime.handle('arm', {'confirmation_token': token})['lease']
        token = runtime.handle('prepare', {'operation': 'takeoff', 'lease': lease})['confirmation_token']
        # Set this before sending: a lost reply does not prove no takeoff occurred.
        attempted = True
        runtime.handle('takeoff', {'confirmation_token': token, 'lease': lease})
        deadline = clock() + 10
        airborne_at = None
        while clock() < deadline:
            status = runtime.status()
            if preview is not None: preview.update(status)
            state = status.get('state')
            if state == 'flying':
                if airborne_at is None:
                    airborne_at = clock()
                    print('Flight observed; requesting landing after the short hover.', flush=True)
                if clock() - airborne_at >= hover_seconds:
                    break
            elif state not in ('taking_off', 'armed'):
                raise RuntimeError('flight did not reach stable flying state: ' + str(state))
            runtime.handle('keepalive', {'lease': lease})
            sleep(.1)
        else:
            raise RuntimeError('takeoff or hover timed out')
        # Mark the request before sending; never replay an uncertain mutation.
        land_requested = True
        runtime.handle('land')
        landing_attempts = 1
        last_landing_request = clock()
        print('Landing requested; monitor the drone and use your independent override if needed.', flush=True)
        deadline = clock() + 30
        landing_confirmation_offered = False
        landing_fault_reported = False
        while clock() < deadline:
            status = runtime.status()
            if preview is not None: preview.update(status)
            sample = status.get('telemetry') or {}
            if sample.get('flying') is False and sample.get('motors_on') is False:
                if read_input('Type GROUNDED only when the drone is visibly on the ground and its propellers have stopped: ').strip() != 'GROUNDED':
                    raise RuntimeError('landing was not visually confirmed')
                token = runtime.handle('prepare', {'operation': 'ground'})['confirmation_token']
                runtime.handle('confirm_ground', {'confirmation_token': token})
                result = runtime.status()
                if result.get('possible_airborne') is not False:
                    raise RuntimeError('supervisor has not cleared the airborne state')
                print('Ground and stopped propellers confirmed.', flush=True)
                return result
            if status.get('state') == 'fault' and not landing_fault_reported:
                print('Automatic landing was not confirmed. Take over with the independent DJI controls now.',
                      file=sys.stderr, flush=True)
                landing_fault_reported = True
            if status.get('landing_confirmation', {}).get('available') and not landing_confirmation_offered:
                landing_confirmation_offered = True
                answer = read_input('The drone requests a final landing confirmation. If the landing area is clear, type LAND to confirm, or press Enter to use your independent control: ').strip()
                if answer == 'LAND':
                    current = runtime.status()
                    if current.get('landing_confirmation', {}).get('available') is True:
                        token = runtime.handle('prepare', {'operation': 'landing'})['confirmation_token']
                        runtime.handle('confirm_landing', {'confirmation_token': token})
                        print('Final landing confirmation sent; continue watching the drone.', flush=True)
                    else:
                        print('Landing confirmation is no longer available; continue watching the drone.', flush=True)
                else:
                    print('No final confirmation sent; use your independent control if needed.', flush=True)
            if (not landing_confirmation_offered and landing_attempts < 3
                    and clock() - last_landing_request >= 5 and can_retry_landing(status)):
                # Update before sending: an uncertain reply must never be replayed.
                landing_attempts += 1
                last_landing_request = clock()
                runtime.handle('land')
                print(f'Landing requested again ({landing_attempts}/3); continue watching the drone.', flush=True)
            sleep(.1)
        raise RuntimeError('landing not confirmed; use your independent override')
    finally:
        if attempted and not land_requested:
            try:
                status = runtime.status()
                if preview is not None: preview.update(status)
                if status.get('connected') and status.get('possible_airborne'):
                    runtime.handle('land')
                    print('Landing requested after interruption.', flush=True)
            except Exception:
                print('Could not confirm a landing request; use your independent override.', file=sys.stderr, flush=True)
        # close() requests the independent owner to stop safely. It refuses to
        # kill or falsely report cleanup for an aircraft that may be airborne.
        runtime.close()


def main():
    parser = argparse.ArgumentParser(description='Experimental supervised Neo 2 vertical demo')
    parser.add_argument('--interface')
    parser.add_argument('--ble-id', help='macOS Bluetooth identifier, if the drone is not advertising')
    parser.add_argument('--no-browser', action='store_true', help='print the local preview URL without opening a browser')
    parser.add_argument('--video', action=argparse.BooleanOptionalAction, default=True,
                        help='enable video preview (default); --no-video runs the supervised demo without video')
    parser.add_argument('--hover-seconds', type=int, default=3, choices=range(1, 6))
    args = parser.parse_args()
    if sys.platform != 'darwin':
        raise SystemExit('This demo currently requires macOS')
    from neo2_vision.mac_flight_connection import MacFlightConfig
    from neo2_vision.vendor.macos_owner import ProcessControllerRuntime
    try:
        settings = load_drone_config()
        ble_id = args.ble_id or settings.ble_id
        address = str(uuid.UUID(ble_id)).upper() if ble_id else identify_device(asyncio.run(scan(8)))
        root = Path(__file__).resolve().parents[2] / 'private'
        key = hashlib.sha256(address.upper().encode()).hexdigest()[:12]
        state = root / key
        state.mkdir(mode=0o700, parents=True, exist_ok=True)
        if state.is_symlink() or state.stat().st_uid != os.geteuid() or state.stat().st_mode & 0o077:
            raise RuntimeError('unsafe private state directory')
        config = MacFlightConfig(interface=args.interface or settings.interface, ble_address=address,
            wlm_identity_file=state/'wlm.json', ble_identity_file=state/'ble.json',
            capture_directory=state/'sessions', incident_directory=state/'incidents',
            duration=120, vertical_trial=True, camera_preview=args.video)
        journal = state / ('o-' + secrets.token_hex(4) + '.jsonl')
        from neo2_vision.preview import PreviewServer
        runtime = ProcessControllerRuntime(journal, mode='flight', flight_config=config)
        preview = PreviewServer(config.capture_directory) if args.video else None
        try:
            if preview is not None:
                url = preview.start()
                print('Live view:', url, flush=True)
                if not args.no_browser:
                    webbrowser.open(url)
            run_demo(runtime, hover_seconds=args.hover_seconds, preview=preview, video_enabled=args.video)
        finally:
            if preview is not None:
                preview.close()
            if not runtime.closed:
                runtime.close()
    except (Exception, KeyboardInterrupt) as error:
        print('Demo did not complete:', error, file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
