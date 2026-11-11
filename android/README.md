# Free Neo for Android

Open this directory in Android Studio with JDK 17 and Android SDK Platform 35.
Run the app on an Android 12 or newer device with Bluetooth LE for discovery.
The flight simulator also works without Bluetooth hardware.

- **Scan for Neo 2** requests Nearby devices permission and lists candidates
  advertising the Neo 2 name or expected Bluetooth service. Selecting a
  candidate opens GATT, checks for the service, then closes the connection.
- **Flight simulator** lets you connect, arm, take off, and land in memory.
  Leaving the app resets the simulator.

DJI application pairing, Wi-Fi association, session negotiation, and telemetry
are pending. The app has no aircraft flight command path. Automatic landing
remains unverified on the real aircraft.

Run the simulator state tests with JDK 17 from this directory.

```sh
classes=$(mktemp -d)
javac --release 17 -d "$classes" app/src/main/java/dev/freeneo/app/FlightSimulation.java tests/FlightSimulationTest.java
java -cp "$classes" dev.freeneo.app.FlightSimulationTest
```
