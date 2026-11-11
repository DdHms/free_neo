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

With the aircraft on the ground and its Wi-Fi connected, enter the drone IPv4
address and UDP port from your local settings and tap **Read telemetry**. The
app saves these values on the phone. The eight-second probe binds its UDP socket
to the drone's Wi-Fi and displays fresh received battery, flight and motor
state when available. The probe opens a DJI transport session and sends receive
acknowledgements. It does not negotiate DJI flight control or send aircraft
commands. Android telemetry has been tested with a recorded session and still
needs a live phone test. Automatic landing remains unverified on the aircraft.

Run the simulator, pairing, and telemetry tests with JDK 17 from this directory.

```sh
classes=$(mktemp -d)
javac --release 17 -d "$classes" app/src/main/java/dev/freeneo/app/{FlightSimulation,PairingExchange,TelemetryProtocol}.java tests/*.java
java -cp "$classes" dev.freeneo.app.FlightSimulationTest
java -cp "$classes" dev.freeneo.app.PairingExchangeTest
java -cp "$classes" dev.freeneo.app.TelemetryProtocolTest
```
