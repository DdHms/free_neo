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
import android.os.SystemClock;
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
import java.util.UUID;
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
    private Button scan, connect, arm, takeOff, land, confirmLanding, loseLink;
    private boolean simulatedLinkDown;
    private final Runnable simulatedTouchdown = () -> {
        simulation.observeLanding(false, false);
        renderSimulation();
    };
    private final Runnable simulatedConfirmationRequest = () -> {
        simulation.requestFinalConfirmation();
        renderSimulation();
    };
    private final Runnable simulationTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (!simulatedLinkDown) simulation.heartbeat(now);
            simulation.checkLink(now);
            renderSimulation();
            if (simulation.state() != FlightSimulation.State.LINK_LOST) {
                handler.postDelayed(this, 100);
            }
        }
    };
    private boolean started;
    private final Runnable simulatedHandshake = () -> {
        if (simulation.connected(SystemClock.elapsedRealtime())) simulationTick.run();
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
        button(page, "Read telemetry", () -> readTelemetry(false));
        button(page, "Check flight service (ground only)", () -> readTelemetry(true));

        label(page, "Flight simulator").setTextSize(20);
        simulationStatus = label(page, "");
        connect = button(page, "Connect simulator", () -> {
            if (!simulation.connect()) return;
            renderSimulation();
            handler.postDelayed(simulatedHandshake, 700);
        });
        arm = button(page, "Arm simulator", () -> { simulation.arm(); renderSimulation(); });
        takeOff = button(page, "Simulate takeoff", () -> { simulation.takeOff(); renderSimulation(); });
        land = button(page, "Simulate landing", () -> {
            if (!simulation.land()) return;
            renderSimulation();
            handler.postDelayed(simulatedConfirmationRequest, 600);
        });
        confirmLanding = button(page, "Confirm simulated landing", () -> {
            if (!simulation.confirmLanding()) return;
            renderSimulation();
            handler.postDelayed(simulatedTouchdown, 900);
        });
        loseLink = button(page, "Simulate link loss", () -> {
            simulatedLinkDown = true;
            handler.removeCallbacks(simulatedConfirmationRequest);
            handler.removeCallbacks(simulatedTouchdown);
            renderSimulation();
        });
        button(page, "Reset simulator", this::resetSimulation);
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

    private void readTelemetry(boolean checkService) {
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
        telemetryStatus.setText(checkService ? "Checking DJI Wi-Fi service activation…" : "Opening a read-only DJI session…");
        var identities = getSharedPreferences("wifi-session", MODE_PRIVATE);
        String identity = identities.getString("identity", null);
        if (identity == null) {
            identity = UUID.randomUUID().toString().replace("-", "");
            identities.edit().putString("identity", identity).apply();
        }
        probe = new TelemetryProbe(network, host, port, identity, checkService, message -> handler.post(() -> {
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

    private void resetSimulation() {
        handler.removeCallbacks(simulatedHandshake);
        handler.removeCallbacks(simulationTick);
        handler.removeCallbacks(simulatedConfirmationRequest);
        handler.removeCallbacks(simulatedTouchdown);
        simulatedLinkDown = false;
        simulation.reset();
        renderSimulation();
    }

    private void renderSimulation() {
        FlightSimulation.State state = simulation.state();
        simulationStatus.setText(simulatedLinkDown && state != FlightSimulation.State.LINK_LOST
            ? "Simulated heartbeat interrupted. Waiting for the watchdog." : switch (state) {
            case DISCONNECTED -> "Simulator disconnected";
            case CONNECTING -> "Simulating connection";
            case GROUNDED -> "Simulated aircraft on the ground";
            case ARMED -> "Simulated aircraft armed";
            case AIRBORNE -> "Simulated aircraft airborne";
            case LANDING -> "Simulated landing in progress";
            case AWAITING_CONFIRMATION -> "Simulated aircraft requests final landing confirmation";
            case LINK_LOST -> "Simulated watchdog detected link loss. Flight state unknown.";
        });
        connect.setEnabled(state == FlightSimulation.State.DISCONNECTED);
        arm.setEnabled(!simulatedLinkDown && state == FlightSimulation.State.GROUNDED);
        takeOff.setEnabled(!simulatedLinkDown && state == FlightSimulation.State.ARMED);
        land.setEnabled(!simulatedLinkDown && state == FlightSimulation.State.AIRBORNE);
        confirmLanding.setEnabled(!simulatedLinkDown && state == FlightSimulation.State.AWAITING_CONFIRMATION);
        loseLink.setEnabled(!simulatedLinkDown && state != FlightSimulation.State.DISCONNECTED
            && state != FlightSimulation.State.CONNECTING);
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
        resetSimulation();
        super.onStop();
    }

    @Override protected void onDestroy() {
        drone.close();
        super.onDestroy();
    }
}
