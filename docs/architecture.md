# DJI Neo 2 Vision Controller — Architecture

**Status:** Initial design / implementation plan  
**Primary development platform:** macOS, Python 3.11+  
**Target hardware:** DJI Neo 2; Android phone for future control inputs  
**Primary goal:** Build an independent, low-latency drone video and control application supporting user-selected object following and phone-based motion control.

## 1. Product intent

The application connects to a DJI Neo 2 without requiring the DJI Fly UI during operation, displays its live video, detects objects, lets a user select and lock onto a target, and eventually commands the drone to follow that target. Two alternative phone-driven control styles should remain available: (a) IMU gesture/tilt control and (b) ARCore-based 6-DoF spatial control. Start with a Python prototype on a Mac; design interfaces so the UI and perception stack can later migrate to Android.

### Non-goals for the first milestone
- Autonomous takeoff, following, or flight commands.
- Bypassing DJI authentication, safety restrictions, geofencing, or firmware protections.
- Assuming that protocols or commands for the original DJI Neo work unchanged on Neo 2.
- Assuming that an unverified community repository provides working Neo 2 connectivity.

## 2. Key assumptions and unknowns

1. **Connectivity is the main feasibility gate.** Verify actual Neo 2 BLE discovery/pairing, Wi-Fi association, authentication, video transport, telemetry, and command channels on the user's hardware. Do not invent packet layouts, addresses, ports, or crypto keys.
2. **macOS support is unproven.** Existing reverse-engineering clients may rely on Linux-specific BLE, network, or packet-capture APIs; isolate OS-specific functionality.
3. **Video and commands may use distinct protocols.** Establish video reception first; verify telemetry and control independently.
4. **Control semantics must be measured.** Determine whether the drone accepts body-frame velocities, attitude, yaw rate, or another interface; confirm units, rate, acknowledgments, and watchdog behavior before enabling movement.
5. **On-device vision is feasible but latency-sensitive.** Benchmark decoding, inference, tracking, and UI separately on the Mac.
6. **Phone inertial sensing cannot reliably provide long-term displacement alone.** IMU-only mode maps gestures/tilt to bounded commands; spatial mode requires visual-inertial pose tracking such as ARCore.
7. **Permissions and compliance:** Respect DJI terms, radio rules, privacy, local flight regulations, and third-party code licenses. Use only the user's drone and authorized software/traffic.

## 3. High-level architecture

```text
                          DJI Neo 2
                   camera / telemetry / FC
                             |
                     BLE + Wi-Fi (TBD)
                             |
                 [Drone Transport Adapter]
                    /       |          \
              Video RX  Telemetry RX   Command TX
                 |          |             ^
           [Decoder]   [State Store]      |
                 |          |       [Safety Supervisor]
                 v          |             ^
           [Frame Bus]      |       [Command Arbiter]
              /   \         |          /    |     \
         [Viewer] [Detector] |   [Tracker] [IMU] [ARCore]
                      |      |       ^        ^       ^
                 [Target Tracker]   |        |       |
                      |             |    [Android Sensor Bridge]
                 [Visual Servo] ----+
                      |
                [Command Proposal]
```

**Safety supervisor** is the only component allowed to forward flight commands to the drone adapter. All controllers generate proposals, not direct actuator writes. Only one active controller owns flight commands at a time. Explicit disarm/hover behavior and independent emergency override take priority.

## 4. Repository layout

```text
neo2-vision/
├── architecture.md
├── README.md
├── pyproject.toml
├── .env.example
├── src/neo2_vision/
│   ├── app.py                 # startup and dependency wiring
│   ├── config.py
│   ├── transport/
│   │   ├── base.py            # abstract drone adapter
│   │   ├── neo2.py            # verified Neo 2 implementation only
│   │   └── mock.py            # replay/simulated drone
│   ├── video/
│   │   ├── decoder.py         # PyAV / FFmpeg
│   │   ├── frames.py          # timestamped frame bus
│   │   └── recording.py
│   ├── perception/
│   │   ├── detector.py        # pluggable detector (YOLO candidate)
│   │   ├── tracker.py         # persistent selected target
│   │   └── selection.py       # click-to-select / reacquisition
│   ├── control/
│   │   ├── types.py           # FlightCommand, mode, timestamps
│   │   ├── arbiter.py
│   │   ├── safety.py
│   │   ├── visual_servo.py
│   │   ├── imu_gesture.py
│   │   └── spatial_pose.py
│   ├── phone/
│   │   ├── bridge.py          # authenticated sensor transport
│   │   └── schemas.py
│   ├── ui/
│   │   └── main_window.py     # PySide6 viewer/controls
│   └── diagnostics/
│       ├── logging.py
│       └── latency.py
├── android/                   # later: IMU/ARCore bridge
├── scripts/
│   ├── inspect_environment.py
│   └── replay_video.py
├── tests/
│   ├── test_control_safety.py
│   ├── test_target_selection.py
│   └── test_mock_transport.py
└── samples/                   # gitignored video/telemetry captures
```

## 5. Core contracts

### DroneAdapter

Expose asynchronous operations: `discover()`, `connect()`, `disconnect()`, `video_frames()`, `telemetry()`, `send_command(command)`, and `emergency_stop()` **only where verified supported**. Explicit capability flags (`video`, `telemetry`, `flight_commands`, `emergency_stop`) prevent UI features from silently assuming support. Use a mock adapter for all early tests.

### Timestamped video and telemetry

Each decoded frame carries monotonic capture/arrival timestamps, frame sequence, resolution, and optionally camera calibration. Telemetry includes monotonic timestamp, battery, connection state, and only fields actually provided by the device. Track stale streams and clock skew.

### Flight command

```python
from dataclasses import dataclass

@dataclass(frozen=True)
class FlightCommand:
    forward: float  # normalized [-1, 1], drone body frame
    right: float    # normalized [-1, 1]
    up: float       # normalized [-1, 1]
    yaw: float      # normalized [-1, 1]; intended yaw-rate input
```

These are **internal normalized requests**, not assumed DJI wire-format values. The transport adapter converts to verified units, ranges, and axis conventions. Commands also need source, creation timestamp, expiry, and arming state in an enclosing `CommandProposal` structure.

### Controller interface

`Controller.update(observation, drone_state) -> CommandProposal | None`. Implementations: `VisualServoController`, `IMUGestureController`, `SpatialPoseController`. The arbiter selects exactly one mode. On invalid input or stale observations, it emits a neutral request and the safety supervisor decides the validated hold/hover response.

## 6. Video and perception pipeline

1. Receive video through a verified Neo 2 transport, or use a recorded file/mock source.
2. Decode with PyAV/FFmpeg; prefer bounded queues that drop old frames over accumulating latency.
3. Detect objects using a pluggable, benchmarked model; start with a small YOLO variant and choose runtime after profiling Apple Silicon CPU/MPS/Core ML options.
4. Draw boxes and labels in the PySide6 viewer; tap/click a detection to select it.
5. Maintain selected target identity with a single-target tracker or detector + association; keep a confidence/lost state. Never silently switch to a different object.
6. Produce normalized image-space center error, bounding-box scale, confidence, and timestamp for visual servoing.
7. On low confidence, occlusion, stale video, or lost target, request hover/stop rather than continuing blind motion.

**Initial servo strategy:** yaw-only image centering, then conservative forward/backward range regulation using apparent target size only after controlled calibration. Image size is not a reliable metric distance without additional assumptions/sensing. Do not assume the drone can avoid obstacles from a monocular image alone.

## 7. Phone input modes

### A. IMU gesture/tilt
- Android `TYPE_ROTATION_VECTOR` for orientation and `TYPE_LINEAR_ACCELERATION` for gravity-compensated acceleration.
- Calibration defines neutral orientation; use dead zones, low-pass filtering, rate limits, and a held enable/dead-man control.
- Map tilt to proportional yaw/strafe/forward commands, or short acceleration gestures to bounded command pulses.
- Do **not** integrate acceleration twice to estimate sustained 3D position.

### B. ARCore spatial controller
- Android ARCore provides relative 6-DoF phone pose when camera tracking is available.
- Calibrate a reference pose and map bounded relative translation/rotation to drone command proposals.
- Require tracking-quality checks, workspace bounds, gain limits, and neutral-on-tracking-loss behavior.
- Requires an Android app; cannot be fully prototyped using only the Mac's sensors.

### Bridge
- Phone -> Mac via a local authenticated WebSocket or UDP protocol (choose after testing); include monotonic timestamps, sequence IDs, pose/IMU data, tracking confidence, and enable state.
- Network disconnect or missing enable signal must disable movement.

## 8. Safety and testing constraints

- Default **disarmed/read-only**. Never send movement on startup, reconnection, or mode changes.
- Implement explicit arming, dead-man input, manual override, and independent emergency stop; verify what the drone actually supports before relying on it.
- Enforce command freshness, telemetry freshness, axis bounds, speed/yaw limits, acceleration limits, and geofence/altitude constraints where available. Video-dependent modes require fresh video; the explicitly selected no-video vertical demo is the exception described below.
- Define behavior for lost target, low-confidence tracking, dropped Wi-Fi, lost phone bridge, stale video, and application crash. Verify hardware failsafes rather than assuming them.
- Bench-test with motors disabled/propellers removed where manufacturer guidance permits; then conduct controlled low-risk tests with an observer and clear surroundings.
- Do not bypass manufacturer safety controls.

### Decision — 2026-11-11: supervised no-video vertical demo

The operator may explicitly select a no-video mode for the bounded, supervised vertical takeoff-and-landing demo. This supersedes the blanket video-freshness requirement for that specific mode, to allow comparison with the earlier no-preview configuration. The operator must keep the aircraft in direct sight and have an independently verified override ready. Explicit arming, ground confirmation, fresh control and aircraft telemetry, battery checks, watchdog, landing supervision, and recovery requirements still apply. Video-dependent detection, tracking, visual servoing, and autonomous following must not run without fresh video. This is an approved design exception; safe landing without video has not yet been established on the hardware.

## 9. Implementation phases and acceptance criteria

### Phase 0 — Repo bootstrap and feasibility audit
- Create Python package, tests, structured logging, mock transport, and sample video replay.
- Inspect candidate open-source implementations and licenses; compare original Neo vs Neo 2.
- Audit macOS-specific BLE/Wi-Fi limitations and enumerate unknown protocol steps.
- **Pass:** `pytest` works; video replay renders; documented connectivity feasibility matrix exists.

### Phase 1 — Live video (read-only)
- Connect authorized Neo 2; receive/decode/display frames with timestamps.
- Measure FPS, end-to-end latency, frame loss, and reconnect behavior.
- **Pass:** stable live preview without DJI Fly UI, with no flight commands issued.

### Phase 2 — Vision UX (offline first)
- Object detector overlay, click-to-select, persistent tracker, lost-target indication.
- **Pass:** recorded and live video show stable target selection and reacquisition behavior without identity jumps.

### Phase 3 — Telemetry and command characterization
- Read telemetry; identify verified command format, axis mapping, rate limits, acknowledgments, and failsafes.
- Build command emulator/mock and unit tests for safety gating.
- **Pass:** bounded, independently verifiable command handling in safe test conditions.

### Phase 4 — Phone input prototypes
- Android sensor bridge with IMU gesture mode, then ARCore relative pose mode.
- Visualize command proposals on the Mac before allowing drone output.
- **Pass:** both input methods can be compared in simulation with reliable dead-man behavior.

### Phase 5 — Controlled closed-loop flight
- Start with yaw-only target centering; only then add slow translation and distance control.
- **Pass:** safe loss-of-target behavior, measured command-to-response latency, and operator override.

### Phase 6 — Android migration
- Move vision/control to Android; reuse schemas and validated protocol behavior.
- **Pass:** phone-only live view and selected supported modes, without Mac relay.

## 10. Engineering decisions to keep reversible

- Do not couple PySide6 widgets to the transport protocol.
- Do not couple detector output directly to motor commands.
- Prefer typed interfaces and dependency injection for transport, detector, tracker, and controller.
- Support mock/replay mode without a drone attached.
- Keep raw captures and secrets out of Git; `.env.example` contains placeholders only.
- Pin dependencies and record provenance/licenses of reverse-engineered code.

## 11. Initial Mac setup

```bash
# Prerequisites: Xcode Command Line Tools and Homebrew
xcode-select --install  # only if not already installed
brew install python@3.11 ffmpeg git
mkdir -p ~/Projects/neo2-vision
cd ~/Projects/neo2-vision
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install numpy opencv-python av PySide6 pytest bleak
```

Object-detection model/runtime and hardware-specific protocol dependencies should be pinned after the feasibility audit, not installed blindly.

## 12. First coding-agent task

> Read `architecture.md`. Bootstrap the `neo2-vision` Python project with a `pyproject.toml`, typed interfaces for `DroneAdapter`, `FlightCommand`, and `CommandProposal`, a mock adapter, a PySide6 live/replay viewer, timestamped frame bus, structured logging, and pytest tests. Implement **read-only replay mode first**, using a local video file or synthetic frames. Do not invent DJI Neo 2 protocol details or issue real flight commands. Add a `docs/connectivity-audit.md` that inventories candidate GitHub repositories, exact source evidence, license, Neo 2 compatibility, and macOS blockers. Keep actual hardware transport behind an unimplemented adapter until independently verified.

## 13. Definition of MVP

A Mac application that receives a real Neo 2 video feed, displays detections, lets the operator select one object, and reliably tracks it on screen. Drone following and phone control are subsequent safety-gated milestones, not prerequisites for this MVP.
