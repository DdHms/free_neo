package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ServiceCapabilitiesTest {
    public static void main(String[] args) {
        var service = new ServiceCapabilities("test-android");
        byte[] serial = "test-neo".getBytes(StandardCharsets.US_ASCII);
        int peer = ServiceCapabilities.hash(serial);
        byte[] beacon = new byte[55];
        beacon[0] = 5;
        beacon[3] = 36;
        System.arraycopy(serial, 0, beacon, 4, serial.length);
        beacon[49] = 1;
        beacon[51] = 0x40;
        byte[] push = PairingExchange.frame(0xe9, 0xee, 0, 0, 0x51, 0x13, beacon);
        service.receive(push, peer ^ 1, -1, 10);
        check(service.nextQuery(123, 1, 10) == null);
        service.receive(push, peer, -1, 10);
        check(service.nextQuery(123, 1, 10) != null);
        check(service.nextQuery(123, 1, 10) == null);
        byte[] reply = ByteBuffer.allocate(49).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[] {0, 0x20, 0, 1, 0}).putInt(peer).putShort((short) 38)
            .put(new byte[] {6, 17, 0x20}).putLong(0).putLong(0)
            .put(new byte[] {10, 17, 0x20}).putLong(0).putLong(0x6c).array();
        service.receive(PairingExchange.frame(0xe9, 0xee, 124, 0x80, 0x51, 0x41, reply), peer, -1, 20);
        check(service.state(20) == ServiceCapabilities.State.AWAITING_REPLY);
        service.receive(PairingExchange.frame(0xe9, 0xee, 123, 0x80, 0x51, 0x41, reply), peer, -1, 30);
        check(service.state(30) == ServiceCapabilities.State.SUPPORTED);
        check(service.state(2010) == ServiceCapabilities.State.FAILED);
        System.out.println("Peer binding, capability reply, and expiry checks passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected capability state");
    }
}
