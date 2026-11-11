# Neo 2 discovery prototype

Python 3.11+ is required. Create a virtual environment, install this project with `pip install -e .`, then run `neo2-discover`. The scan is read-only and lists nearby BLE advertisements; a device name alone does not establish that it is a Neo 2. On macOS, the application running Python needs Bluetooth permission in Privacy & Security settings. Addresses displayed on macOS are system-generated identifiers, not necessarily hardware MAC addresses.

## Local connection settings

Copy `neo2.example.json` to `neo2.local.json` in the project root before running
a live session. Fill in the aircraft's IPv4 address (`host`), UDP service port
(`port`), Wi-Fi subnet in CIDR notation (`network`), and the Mac's Wi-Fi
interface (`interface`). Use `neo2-discover` to get the macOS Bluetooth
identifier for `ble_id`. The supervised flight demo can discover a single
advertising aircraft when this field is blank. The video-only and existing-feed
configurations require the identifier.

Git ignores `neo2.local.json` and `private/`. The preview uses a loopback-only
address and lets the OS choose a free port.


Run `neo2-wifi-scan` to list nearby Wi-Fi networks whose names match Neo 2, without joining them. A name is only a candidate, not proof of identity.

To connect to a discovered device and inspect its Bluetooth services, run `neo2-discover --probe DEVICE_ID`; this does not perform the Neo 2 setup handshake.

The transport interface separates platform-specific discovery and connection from application logic to help a later Android implementation. The mock adapter only records commands locally. The Neo 2 adapter deliberately rejects connection and flight commands until the protocol and safety behavior are verified on the user's hardware. This prototype does not fly the drone.

For a read-only Wi-Fi service and telemetry check, install `pip install -e ".[session]"`, connect the Mac to the drone’s Wi-Fi, and run `neo2-observe`. The command uses a pinned experimental Neo 2 reference client, switches the drone’s Wi-Fi service, and sends no flight commands. It discards temporary captures on exit. The reference client’s `application_ready` flag is always false; it is not a connection test. Observed telemetry does not establish flight readiness.

Run `neo2-observe --preflight` to inspect the reference flight observer without sending flight commands. Its reported problems include missing position and unconfirmed ground state when applicable; these are not authorization to fly. The upstream vertical-trial profile has additional safety gates and has not been ported to macOS here.

The PyCharm configuration **Neo 2 Watchdog Simulation (no drone)** demonstrates a simulated takeoff and watchdog-triggered landing after parent heartbeat loss. It does not operate the aircraft. The real macOS flight watchdog and airborne recovery remain under development.

The **Neo 2 Supervised Flight Demo** PyCharm configuration starts a loopback-only live-view page, waits for fresh video and telemetry, and requires explicit operator confirmation before any takeoff. The preview is intended as assistance and can be delayed. The demo and its macOS watchdog are experimental; a successful landing request is not proof of landing. The aircraft must remain in sight with an independently verified means of intervention. An unresolved prior-flight incident blocks a new takeoff and must not be deleted or bypassed.

The **Neo 2 View Existing Feed (no flight)** configuration opens the latest fresh camera preview from an already-running owner; it does not establish a drone connection or send commands. The **Neo 2 Video Only (no flight)** configuration can start a separate video-only session when no owner holds the aircraft and recovery locks. Both show a local browser preview; frames older than three seconds are not shown as live.

The supervised demo accepts `--video` (default) and `--no-video`. With `--no-video`, it skips camera preview while retaining the supervised flight and landing checks. PyCharm includes **Neo 2 Supervised Flight (no video)** for this mode. Keep the drone in direct sight with an independent override ready. Video-dependent following requires fresh video.
