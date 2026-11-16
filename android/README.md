# Free Neo for Android

Open this directory in Android Studio with JDK 17 and Android SDK Platform 35.
Run the app on an Android 12 or newer device with Bluetooth LE for discovery.

- **Scan for Neo 2** requests Nearby devices permission and lists candidates
  advertising the Neo 2 name or expected Bluetooth service. Select a candidate
  to run DJI Bluetooth pairing. If the aircraft signals a pairing request,
  follow its power-button prompt. Approve Android's Wi-Fi prompt to join the
  aircraft's local network. After a successful Wi-Fi connection, **Reconnect**
  remembers that aircraft's Bluetooth address and the existing app pairing identity.
  If the aircraft still recognizes the app, a power-button press is unnecessary.
  Follow any confirmation prompts from the aircraft or Android. Scan again if the
  saved aircraft is unreachable. Use **Disconnect drone** to release the connection.

Once Android joins the drone’s Wi-Fi, the app automatically opens the DJI
session, reads telemetry, and negotiates the flight command service. The
app discovers the endpoint from the drone’s Wi-Fi automatically. Takeoff becomes available when the
service and ground telemetry are ready. Airborne telemetry opens the Flight
tab with the live preview and manual controls. Landing controls are on both tabs.
After requesting landing, **Confirm landing
area is clear** is available only while the aircraft reports a fresh
confirmation request. Visually inspect the area before confirming. An
acknowledgement reports command acceptance. The app uses flight and motor
telemetry to report touchdown. Keep independent controls available during
testing. The live Android flight path has not been verified on aircraft hardware.

The app starts the HEVC decoder when the DJI session opens. The Flight tab reveals the preview above the control deck in portrait, and behind the sticks in
landscape. Rotating while movement is armed releases the sticks and disarms
movement. The red camera button starts or stops recording on the aircraft
when recording is available. **Arm manual movement**
requires current preview frames, fresh airborne telemetry, and at least 20%
battery. Hold and drag the left stick to climb, descend or rotate. Hold and
drag the right stick to translate. Releasing a stick centers its axes. Losing
preview, telemetry or app focus stops movement input. Camera up and down
request level and 30° down respectively. A decoder error requires reopening
the flight session. The aircraft's response to these new controls requires
hardware verification.

Open **Controls** on the Flight tab and turn on **Object detection**. The
bundled [EfficientDet-Lite0 model](https://huggingface.co/litert-community/efficientdet) runs on the phone and shows people, bicycles,
cars, and motorcycles. It samples the live preview up to eight times per
second. Track IDs can change after a subject is obscured. Detection is off
by default and runs while the Flight tab is visible.

Run the Android logic tests with JDK 17 from this directory.

```sh
classes=$(mktemp -d)
javac -encoding UTF-8 --release 17 -d "$classes" app/src/main/java/dev/freeneo/app/{PairingExchange,TelemetryProtocol,FlightCommand,ServiceCapabilities,WifiServiceSwitch,LiveFlight,ManualControl,HevcAccessUnits,TargetAssociation,TargetFraming,SubjectTracks}.java tests/*.java
java -cp "$classes" dev.freeneo.app.PairingExchangeTest
java -cp "$classes" dev.freeneo.app.TelemetryProtocolTest
java -cp "$classes" dev.freeneo.app.FlightCommandTest
java -cp "$classes" dev.freeneo.app.LiveFlightTest
java -cp "$classes" dev.freeneo.app.ServiceCapabilitiesTest
java -cp "$classes" dev.freeneo.app.WifiServiceSwitchTest
java -cp "$classes" dev.freeneo.app.ManualControlTest
java -cp "$classes" dev.freeneo.app.TargetAssociationTest
java -cp "$classes" dev.freeneo.app.TargetFramingTest
java -cp "$classes" dev.freeneo.app.SubjectTracksTest
```

With object detection enabled, tap a box to follow it. The tap starts follow
when flight controls are ready. With **Keep apparent size** enabled, the app
captures an initial size from fresh detections and uses it for forward and
backward corrections. Switch it off for yaw and tilt only. Tap **Stop
follow** or touch a manual stick to stop. A brief detection gap pauses
commands. A brief video freeze also pauses Follow. Follow resumes if video
returns and a fresh detection confirms the same target within the recovery
window. A longer freeze, changed track ID, or lost target requires another tap.
