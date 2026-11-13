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

With the aircraft on the ground and its Wi-Fi connected, tap **Read telemetry**.
The app uses the Neo 2 endpoint (UDP port 9003 and IPv4 address 192.168.2.1)
when it is on the connected subnet, with the drone Wi-Fi IPv4 gateway as an
address fallback. Optional overrides support alternate endpoints. The
app saves the overrides on the phone. The eight-second probe binds its UDP socket
to the drone's Wi-Fi and displays fresh received battery, flight and motor
state when available. The probe opens a DJI transport session, sends receive
acknowledgements, and reports an app-local identity when the aircraft accepts
QoS transport. It reads the bound Neo 2 peer’s advertised Wi-Fi command
capability. **Check flight service (ground only)** additionally attempts the bilateral
service activation exchange. **Open flight controls** keeps the session open and enables **Take off** or
**Land** once the Wi-Fi command service is active and the aircraft reports a
fresh flight and motor state. After requesting landing, **Confirm landing
area is clear** is available only while the aircraft reports a fresh
confirmation request. Visually inspect the area before confirming. An
acknowledgement reports command acceptance. The app uses flight and motor
telemetry to report touchdown. Keep independent controls available during
testing. The live Android flight path has not been verified on aircraft hardware.

Run the simulator, pairing, and telemetry tests with JDK 17 from this directory.

```sh
classes=$(mktemp -d)
javac --release 17 -d "$classes" app/src/main/java/dev/freeneo/app/{FlightSimulation,PairingExchange,TelemetryProtocol,FlightCommand,ServiceCapabilities,WifiServiceSwitch,LiveFlight}.java tests/*.java
java -cp "$classes" dev.freeneo.app.FlightSimulationTest
java -cp "$classes" dev.freeneo.app.PairingExchangeTest
java -cp "$classes" dev.freeneo.app.TelemetryProtocolTest
java -cp "$classes" dev.freeneo.app.FlightCommandTest
java -cp "$classes" dev.freeneo.app.LiveFlightTest
java -cp "$classes" dev.freeneo.app.ServiceCapabilitiesTest
java -cp "$classes" dev.freeneo.app.WifiServiceSwitchTest
```
