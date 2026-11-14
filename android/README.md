# Free Neo for Android

Open this directory in Android Studio with JDK 17 and Android SDK Platform 35.
Run the app on an Android 12 or newer device with Bluetooth LE for discovery.
The flight simulator also works without Bluetooth hardware.

- **Scan for Neo 2** requests Nearby devices permission and lists candidates
  advertising the Neo 2 name or expected Bluetooth service. Select a candidate
  to run DJI Bluetooth pairing. If the aircraft signals a pairing request,
  follow its power-button prompt. Approve Android's Wi-Fi prompt to join the
  aircraft's local network. Use **Disconnect drone** to release the connection.
- **Flight simulator** lets you connect, arm, take off, request landing, and
  confirm landing in memory. **Simulate link loss** stops simulated heartbeats.
  The simulated watchdog disables flight controls after its timeout and shows
  that aircraft state is unknown. Leaving the app resets the simulator.

Once Android joins the drone’s Wi-Fi, the app automatically opens the DJI
session, reads telemetry, and negotiates the flight command service. The
Neo 2 defaults to 192.168.2.1 on UDP port 9003. Optional endpoint overrides
are available on the Connection tab. Takeoff becomes available when the
service and ground telemetry are ready. Airborne telemetry opens the Flight
tab with the live preview and manual controls. Landing controls are on both tabs.
After requesting landing, **Confirm landing
area is clear** is available only while the aircraft reports a fresh
confirmation request. Visually inspect the area before confirming. An
acknowledgement reports command acceptance. The app uses flight and motor
telemetry to report touchdown. Keep independent controls available during
testing. The live Android flight path has not been verified on aircraft hardware.

The app starts the HEVC decoder when the DJI session opens. The Flight tab reveals the preview. **Arm manual movement**
requires current preview frames, fresh airborne telemetry, and at least 20%
battery. Hold and drag the left stick to climb, descend or rotate. Hold and
drag the right stick to translate. Releasing a stick centers its axes. Losing
preview, telemetry or app focus stops movement input. Camera up and down
request level and 30° down respectively. A decoder error requires reopening
the flight session. The aircraft's response to these new controls requires
hardware verification.

Run the simulator, pairing, and telemetry tests with JDK 17 from this directory.

```sh
classes=$(mktemp -d)
javac --release 17 -d "$classes" app/src/main/java/dev/freeneo/app/{FlightSimulation,PairingExchange,TelemetryProtocol,FlightCommand,ServiceCapabilities,WifiServiceSwitch,LiveFlight,ManualControl,HevcAccessUnits}.java tests/*.java
java -cp "$classes" dev.freeneo.app.FlightSimulationTest
java -cp "$classes" dev.freeneo.app.PairingExchangeTest
java -cp "$classes" dev.freeneo.app.TelemetryProtocolTest
java -cp "$classes" dev.freeneo.app.FlightCommandTest
java -cp "$classes" dev.freeneo.app.LiveFlightTest
java -cp "$classes" dev.freeneo.app.ServiceCapabilitiesTest
java -cp "$classes" dev.freeneo.app.WifiServiceSwitchTest
java -cp "$classes" dev.freeneo.app.ManualControlTest
```
