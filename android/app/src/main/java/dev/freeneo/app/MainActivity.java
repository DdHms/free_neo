package dev.freeneo.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int NEARBY_REQUEST = 1;
    private static final long SCAN_MS = 8_000;
    private static final long GATT_MS = 12_000;
    private static final UUID SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FlightSimulation simulation = new FlightSimulation();
    private final Set<BluetoothDevice> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private ScanCallback activeScan;
    private BluetoothGatt activeGatt;
    private final Runnable scanTimeout = () -> finishScan(
        seen.isEmpty() ? "No Neo 2 advertisements found." : "Select a device to check its Bluetooth service.");
    private final Runnable gattTimeout = () -> finishGatt("Bluetooth service check timed out.");
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
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);

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

    private boolean hasNearbyPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestScan() {
        if (hasNearbyPermissions()) startScan();
        else requestPermissions(new String[] {
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        }, NEARBY_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != NEARBY_REQUEST || !started) return;
        if (hasNearbyPermissions()) startScan();
        else discoveryStatus.setText("Allow Nearby devices access to scan.");
    }

    private void startScan() {
        stopScan();
        closeGatt();
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
            && record.getServiceUuids().contains(new ParcelUuid(SERVICE));
        boolean neoName = name != null && name.toUpperCase(Locale.ROOT).startsWith("DJI-NEO2-");
        BluetoothDevice device = result.getDevice();
        if ((!neoName && !serviceAdvertised) || !seen.add(device)) return;
        String displayName = neoName ? name : "Possible Neo 2 (" + seen.size() + ")";
        button(results, "Check " + displayName, () -> checkDevice(device));
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

    private void checkDevice(BluetoothDevice device) {
        stopScan();
        closeGatt();
        discoveryStatus.setText("Checking Bluetooth service…");
        try {
            activeGatt = device.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
                    runOnUiThread(() -> {
                        if (gatt != activeGatt) return;
                        if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                            finishGatt("Bluetooth connection ended before the service check.");
                        } else if (state == BluetoothProfile.STATE_CONNECTED) {
                            try {
                                if (!gatt.discoverServices()) finishGatt("Could not start Bluetooth service discovery.");
                            } catch (SecurityException error) {
                                finishGatt("Nearby devices access was removed.");
                            }
                        }
                    });
                }

                @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    runOnUiThread(() -> {
                        if (gatt != activeGatt) return;
                        if (status != BluetoothGatt.GATT_SUCCESS) finishGatt("Bluetooth service discovery failed.");
                        else if (gatt.getService(SERVICE) == null) finishGatt("Neo 2 Bluetooth service was not found.");
                        else finishGatt("Bluetooth service found. DJI pairing and Wi-Fi are pending.");
                    });
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (activeGatt == null) finishGatt("Could not start Bluetooth connection.");
            else handler.postDelayed(gattTimeout, GATT_MS);
        } catch (SecurityException | IllegalStateException error) {
            finishGatt("Bluetooth connection unavailable. Check Bluetooth and Nearby devices access.");
        }
    }

    private void closeGatt() {
        handler.removeCallbacks(gattTimeout);
        BluetoothGatt previous = activeGatt;
        activeGatt = null;
        if (previous != null) {
            try {
                previous.close();
            } catch (SecurityException ignored) {
                // The permission may have been revoked while connected.
            }
        }
    }

    private void finishGatt(String message) {
        closeGatt();
        discoveryStatus.setText(message);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
    }

    @Override protected void onStop() {
        started = false;
        if (activeScan != null || activeGatt != null) {
            discoveryStatus.setText("Bluetooth check stopped. Scan again to continue.");
        }
        stopScan();
        closeGatt();
        handler.removeCallbacksAndMessages(null);
        simulation.reset();
        renderSimulation();
        super.onStop();
    }
}
