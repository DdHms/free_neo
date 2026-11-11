# Free Neo for Android

Open this directory in Android Studio with JDK 17 and Android SDK Platform 35.
Run the app on an Android 12 or newer device with Bluetooth LE for discovery.
The flight simulator also works without Bluetooth hardware.

- **Scan for Neo 2** requests Nearby devices permission and lists candidates
  advertising the Neo 2 name or expected Bluetooth service. Select a candidate
  to run DJI Bluetooth pairing. If the aircraft signals a pairing request,
  follow its power-button prompt. Approve Android's Wi-Fi prompt to join the
  aircraft's local network. Use **Disconnect drone** to release the connection.
- **Flight simulator** lets you connect, arm, take off, and land in memory.
  Leaving the app resets the simulator.

The Android pairing and Wi-Fi flow still needs physical device validation.
Wi-Fi association is distinct from DJI UDP session negotiation, which is pending
along with live telemetry. The app has no aircraft flight command path.
Automatic landing remains unverified on the real aircraft.

Run the simulator and pairing transcript tests with JDK 17 from this directory.

```sh
classes=$(mktemp -d)
javac --release 17 -d "$classes" app/src/main/java/dev/freeneo/app/{FlightSimulation,PairingExchange}.java tests/*.java
java -cp "$classes" dev.freeneo.app.FlightSimulationTest
java -cp "$classes" dev.freeneo.app.PairingExchangeTest
```
