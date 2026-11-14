package dev.freeneo.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

final class DroneConnection {
    static final UUID SERVICE = uuid("fff0");
    private static final UUID NOTIFY = uuid("fff4");
    private static final UUID WRITE = uuid("fff5");
    private static final UUID CCCD = uuid("2902");
    private static final int MAX_PENDING_CHUNKS = 32;

    private final Context context;
    private final Handler handler;
    private final Consumer<String> status;
    private final Runnable pairingTimeout = () -> fail("Bluetooth pairing timed out.");
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writer, notifier;
    private PairingExchange exchange;
    private boolean writing;
    private ConnectivityManager.NetworkCallback wifi;
    private Network droneNetwork;

    DroneConnection(Context context, Handler handler, Consumer<String> status) {
        this.context = context;
        this.handler = handler;
        this.status = status;
    }

    void connect(BluetoothDevice device) {
        close();
        status.accept("Connecting over Bluetooth…");
        try {
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) fail("Could not open Bluetooth connection.");
            else handler.postDelayed(pairingTimeout, 40_000);
        } catch (IllegalStateException | SecurityException error) {
            fail("Bluetooth unavailable. Check Bluetooth and Nearby devices access.");
        }
    }

    // dispatch checks the current GATT and catches permission revocation.
    @SuppressLint("MissingPermission")
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int result, int state) {
            dispatch(link, () -> {
                if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Drone Bluetooth disconnected.");
                } else if (state == BluetoothProfile.STATE_CONNECTED && !link.discoverServices()) {
                    fail("Could not discover drone Bluetooth services.");
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt link, int result) {
            dispatch(link, () -> {
                if (result != BluetoothGatt.GATT_SUCCESS || link.getService(SERVICE) == null) {
                    fail("Neo 2 Bluetooth service was not found.");
                    return;
                }
                var service = link.getService(SERVICE);
                writer = service.getCharacteristic(WRITE);
                notifier = service.getCharacteristic(NOTIFY);
                var descriptor = notifier == null ? null : notifier.getDescriptor(CCCD);
                if (writer == null || descriptor == null
                        || (writer.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0
                        || (notifier.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                        || !link.setCharacteristicNotification(notifier, true)
                        || !enableNotifications(link, descriptor)) {
                    fail("The drone Bluetooth pairing profile is unavailable.");
                }
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt link, BluetoothGattDescriptor descriptor, int result) {
            dispatch(link, () -> {
                if (!CCCD.equals(descriptor.getUuid()) || exchange != null) return;
                if (result != BluetoothGatt.GATT_SUCCESS) fail("Could not enable pairing notifications.");
                else startExchange();
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt link, BluetoothGattCharacteristic characteristic,
                                                    int result) {
            dispatch(link, () -> {
                if (characteristic != writer || !writing) return;
                writing = false;
                if (result != BluetoothGatt.GATT_SUCCESS) fail("Bluetooth pairing write failed.");
                else writeNext();
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic,
                                                      byte[] value) {
            byte[] copy = value.clone();
            dispatch(link, () -> {
                if (characteristic == notifier && exchange != null) exchange.receive(copy);
            });
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) onCharacteristicChanged(link, characteristic, value);
        }
    };

    private void dispatch(BluetoothGatt link, Runnable callback) {
        handler.post(() -> {
            if (link != gatt) return;
            try {
                callback.run();
            } catch (IllegalArgumentException | IllegalStateException error) {
                fail("The drone rejected pairing or returned an unsupported reply.");
            } catch (SecurityException error) {
                fail("Nearby devices access is required to pair.");
            }
        });
    }

    // Called only through dispatch, which handles permission revocation.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private static boolean enableNotifications(BluetoothGatt link, BluetoothGattDescriptor descriptor) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
        }
        return descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            && link.writeDescriptor(descriptor);
    }

    private void startExchange() {
        var preferences = context.getSharedPreferences("drone-pairing", Context.MODE_PRIVATE);
        String identity = preferences.getString("client-id", null);
        if (identity == null) {
            identity = UUID.randomUUID().toString().replace("-", "");
            preferences.edit().putString("client-id", identity).apply();
        }
        exchange = new PairingExchange(identity, ThreadLocalRandom.current().nextInt(65536),
            new PairingExchange.Listener() {
                @Override public void write(byte[] frame) {
                    for (int offset = 0; offset < frame.length; offset += 20) {
                        if (pending.size() >= MAX_PENDING_CHUNKS) {
                            throw new IllegalArgumentException("Pairing write queue exceeded its limit");
                        }
                        pending.add(Arrays.copyOfRange(frame, offset, Math.min(offset + 20, frame.length)));
                    }
                    writeNext();
                }

                @Override public void confirmationRequired() {
                    status.accept("If the drone’s lights flash for pairing, hold its power button to confirm.");
                }

                @Override public void credentials(byte[] bssid, byte[] ssid, byte[] passphrase) {
                    handler.removeCallbacks(pairingTimeout);
                    joinWifi(bssid, ssid, passphrase);
                }
            });
        status.accept("Waiting for the drone to accept pairing…");
        exchange.start();
    }

    // Called only through dispatch, which handles permission revocation.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void writeNext() {
        if (writing || pending.isEmpty() || gatt == null) return;
        byte[] value = pending.removeFirst();
        writing = true;
        boolean accepted;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            accepted = gatt.writeCharacteristic(writer, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS;
        } else {
            writer.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            accepted = writer.setValue(value) && gatt.writeCharacteristic(writer);
        }
        if (!accepted) throw new IllegalStateException("Bluetooth write did not start");
    }

    private void joinWifi(byte[] bssid, byte[] ssid, byte[] passphrase) {
        try {
            var specifier = new WifiNetworkSpecifier.Builder()
                .setBssid(MacAddress.fromBytes(bssid))
                .setSsid(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(ssid)).toString())
                .setWpa2Passphrase(StandardCharsets.US_ASCII.newDecoder().decode(ByteBuffer.wrap(passphrase)).toString())
                .build();
            var request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
            wifi = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (wifi != this) return;
                    status.accept("Waiting for drone Wi-Fi network settings…");
                }

                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    if (wifi != this || droneNetwork != null || properties.getLinkAddresses().stream()
                            .noneMatch(address -> address.getAddress() instanceof java.net.Inet4Address)) return;
                    droneNetwork = network;
                    status.accept("Drone Wi-Fi connected.");
                }

                @Override public void onUnavailable() {
                    if (wifi == this) fail("Drone Wi-Fi could not connect. Check Wi-Fi and approve Android’s prompt.");
                }

                @Override public void onLost(Network network) {
                    if (wifi == this) fail("Drone Wi-Fi disconnected.");
                }
            };
            status.accept("Approve the drone Wi-Fi connection in Android’s prompt.");
            connectivity().requestNetwork(request, wifi, handler, 45_000);
        } catch (CharacterCodingException | IllegalArgumentException | IllegalStateException | SecurityException error) {
            fail("Could not request drone Wi-Fi. Check Wi-Fi and app permissions.");
        } finally {
            Arrays.fill(passphrase, (byte) 0);
        }
    }

    private ConnectivityManager connectivity() {
        return context.getSystemService(ConnectivityManager.class);
    }

    private void fail(String message) {
        close();
        status.accept(message);
    }

    Network network() { return droneNetwork; }

    void close() {
        droneNetwork = null;
        handler.removeCallbacks(pairingTimeout);
        exchange = null;
        writer = null;
        notifier = null;
        writing = false;
        pending.clear();
        BluetoothGatt previous = gatt;
        gatt = null;
        if (previous != null) {
            try { previous.close(); } catch (SecurityException ignored) { }
        }
        var previousWifi = wifi;
        wifi = null;
        if (previousWifi != null) {
            try { connectivity().unregisterNetworkCallback(previousWifi); }
            catch (IllegalArgumentException | SecurityException ignored) { }
        }
    }

    private static UUID uuid(String shortId) {
        return UUID.fromString("0000" + shortId + "-0000-1000-8000-00805f9b34fb");
    }
}
