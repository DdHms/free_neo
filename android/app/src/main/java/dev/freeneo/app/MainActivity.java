package dev.freeneo.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int NEARBY_REQUEST = 1;
    private static final long SCAN_MS = 8_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FlightSimulation simulation = new FlightSimulation();
    private final Set<BluetoothDevice> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private ScanCallback activeScan;
    private DroneConnection drone;
    private TelemetryProbe probe;
    private EditText hostInput, portInput;
    private TextView telemetryStatus;
    private int probeGeneration;
    private final Runnable scanTimeout = () -> finishScan(
        seen.isEmpty() ? "No Neo 2 advertisements found." : "Select a device to connect.");
    private LinearLayout results;
    private TextView discoveryStatus;
    private TextView simulationStatus;
    private Button scan, connect, arm, takeOff, land;
    private boolean started;
    private final Runnable simulatedHandshake = () -> {
        simulation.connected();
        renderSimulation();
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        scroll.addView(page);
        setContentView(scroll);

        TextView title = label(page, "Free Neo");
        title.setTextSize(32);
        label(page, "Aircraft discovery").setTextSize(20);
        discoveryStatus = label(page, "Scan for a nearby Neo 2.");
        scan = button(page, "Scan for Neo 2", this::requestScan);
        drone = new DroneConnection(this, handler, message -> {
            discoveryStatus.setText(message);
            if (drone.network() == null) stopProbe();
        });
        button(page, "Disconnect drone", () -> {
            stopProbe();
            drone.close();
            discoveryStatus.setText("Drone disconnected.");
        });
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);

        label(page, "Aircraft telemetry").setTextSize(20);
        hostInput = field(page, "Drone IPv4 address", InputType.TYPE_CLASS_PHONE);
        portInput = field(page, "Drone UDP port", InputType.TYPE_CLASS_NUMBER);
        var endpoint = getSharedPreferences("drone-endpoint", MODE_PRIVATE);
        hostInput.setText(endpoint.getString("host", ""));
        portInput.setText(endpoint.getString("port", ""));
        telemetryStatus = label(page, "Connect to drone Wi-Fi to read telemetry.");
        button(page, "Read telemetry", this::readTelemetry);

        label(page, "Flight simulator").setTextSize(20);
        simulationStatus = label(page, "");
        connect = button(page, "Connect simulator", () -> {
            if (!simulation.connect()) return;
            renderSimulation();
            handler.postDelayed(simulatedHandshake, 700);
        });
        arm = button(page, "Arm simulator", () -> { simulation.arm(); renderSimulation(); });
        takeOff = button(page, "Simulate takeoff", () -> { simulation.takeOff(); renderSimulation(); });
        land = button(page, "Simulate landing", () -> { simulation.land(); renderSimulation(); });
        button(page, "Reset simulator", () -> {
            handler.removeCallbacks(simulatedHandshake);
            simulation.reset();
            renderSimulation();
        });
        renderSimulation();
    }

    private EditText field(LinearLayout parent, String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine();
        field.setInputType(inputType);
        parent.addView(field);
        return field;
    }

    private void readTelemetry() {
        var network = drone.network();
        if (network == null) {
            telemetryStatus.setText("Connect to drone Wi-Fi first.");
            return;
        }
        stopProbe();
        String hostText = hostInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        InetAddress host;
        int port;
        try {
            host = InetAddresses.parseNumericAddress(hostText);
            port = Integer.parseInt(portText);
            if (!(host instanceof Inet4Address) || host.isAnyLocalAddress() || host.isLoopbackAddress()
                    || host.isMulticastAddress() || port < 1 || port > 65535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            telemetryStatus.setText("Enter a valid drone IPv4 address and UDP port.");
            return;
        }
        var connectivity = getSystemService(ConnectivityManager.class);
        var properties = connectivity.getLinkProperties(network);
        if (properties == null || properties.getRoutes().stream()
                .noneMatch(route -> !route.hasGateway() && route.matches(host))) {
            telemetryStatus.setText("The address is outside the drone Wi-Fi subnet.");
            return;
        }
        getSharedPreferences("drone-endpoint", MODE_PRIVATE).edit()
            .putString("host", hostText).putString("port", portText).apply();
        int generation = probeGeneration;
        telemetryStatus.setText("Opening a read-only DJI session…");
        probe = new TelemetryProbe(network, host, port, message -> handler.post(() -> {
            if (generation == probeGeneration) telemetryStatus.setText(message);
        }));
        probe.start();
    }

    private void stopProbe() {
        probeGeneration++;
        if (probe != null) {
            probe.close();
            probe = null;
        }
        telemetryStatus.setText("Read telemetry to start a new probe.");
    }

    private TextView label(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setOnClickListener(ignored -> action.run());
        parent.addView(view);
        return view;
    }

    private void renderSimulation() {
        FlightSimulation.State state = simulation.state();
        simulationStatus.setText(switch (state) {
            case DISCONNECTED -> "Simulator disconnected";
            case CONNECTING -> "Simulating connection";
            case GROUNDED -> "Simulated aircraft on the ground";
            case ARMED -> "Simulated aircraft armed";
            case AIRBORNE -> "Simulated aircraft airborne";
        });
        connect.setEnabled(state == FlightSimulation.State.DISCONNECTED);
        arm.setEnabled(state == FlightSimulation.State.GROUNDED);
        takeOff.setEnabled(state == FlightSimulation.State.ARMED);
        land.setEnabled(state == FlightSimulation.State.AIRBORNE);
    }

    private String[] missingPermissions() {
        var needed = new ArrayList<String>();
        for (String permission : new String[] {Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES}) {
            if (permission.equals(Manifest.permission.NEARBY_WIFI_DEVICES) && Build.VERSION.SDK_INT < 33) continue;
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) needed.add(permission);
        }
        if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return needed.toArray(new String[0]);
    }

    private void requestScan() {
        String[] missing = missingPermissions();
        if (missing.length == 0) startScan();
        else requestPermissions(missing, NEARBY_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != NEARBY_REQUEST || !started) return;
        if (missingPermissions().length == 0) startScan();
        else discoveryStatus.setText("Allow Bluetooth and Wi-Fi permissions to discover and connect.");
    }

    private void startScan() {
        stopScan();
        stopProbe();
        drone.close();
        results.removeAllViews();
        seen.clear();
        var manager = getSystemService(BluetoothManager.class);
        var adapter = manager == null ? null : manager.getAdapter();
        try {
            scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                discoveryStatus.setText("Bluetooth LE unavailable. Check that Bluetooth is on.");
                return;
            }
            activeScan = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    runOnUiThread(() -> {
                        if (activeScan == this) showResult(result);
                    });
                }
                @Override public void onScanFailed(int error) {
                    runOnUiThread(() -> {
                        if (activeScan == this) finishScan("Bluetooth scan failed. Code " + error);
                    });
                }
            };
            discoveryStatus.setText("Scanning for Neo 2…");
            scanner.startScan(activeScan);
            handler.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException | IllegalStateException error) {
            finishScan("Bluetooth scan unavailable. Check Bluetooth and Nearby devices access.");
        }
    }

    private void showResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        String name = record.getDeviceName();
        boolean serviceAdvertised = record.getServiceUuids() != null
            && record.getServiceUuids().contains(new ParcelUuid(DroneConnection.SERVICE));
        boolean neoName = name != null && name.toUpperCase(Locale.ROOT).startsWith("DJI-NEO2-");
        BluetoothDevice device = result.getDevice();
        if ((!neoName && !serviceAdvertised) || !seen.add(device)) return;
        String displayName = neoName ? name : "Possible Neo 2 (" + seen.size() + ")";
        button(results, "Connect to " + displayName, () -> {
            stopScan();
            drone.connect(device);
        });
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        ScanCallback previous = activeScan;
        activeScan = null;
        if (scanner != null && previous != null) {
            try {
                scanner.stopScan(previous);
            } catch (SecurityException | IllegalStateException ignored) {
                // Bluetooth or its permission can disappear during the scan.
            }
        }
        scanner = null;
    }

    private void finishScan(String message) {
        stopScan();
        discoveryStatus.setText(message);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
    }

    @Override protected void onStop() {
        started = false;
        stopProbe();
        if (activeScan != null) discoveryStatus.setText("Scan stopped. Scan again to continue.");
        stopScan();
        handler.removeCallbacks(simulatedHandshake);
        simulation.reset();
        renderSimulation();
        super.onStop();
    }

    @Override protected void onDestroy() {
        drone.close();
        super.onDestroy();
    }
}
