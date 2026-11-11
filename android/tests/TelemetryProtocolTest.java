package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

public final class TelemetryProtocolTest implements TelemetryProtocol.Observer {
    private int flightSamples, batterySamples;

    @Override public void flight(boolean flying, boolean motorsOn) {
        check(!flying && !motorsOn);
        flightSamples++;
    }

    @Override public void battery(int percent) {
        check(percent == 63);
        batterySamples++;
    }

    public static void main(String[] args) {
        var test = new TelemetryProtocolTest();
        var protocol = new TelemetryProtocol(0x4321, 0x1238, test);
        check(hex(protocol.open()).equals(
            "30802143000000d2381264006400c005140000640000019001c005140000640014006400c00514000064000101040102"));
        check(hex(protocol.acknowledge()).equals(
            "22802143000004c43812381200000000381238120000000038123812000000000000"));
        byte[] flight = osd(0x43, new byte[37]);
        byte[] batteryPayload = new byte[10];
        batteryPayload[8] = 63;
        byte[] battery = osd(0x55, batteryPayload);
        byte[] aggregate = datagram(1, 34 + flight.length + battery.length);
        buffer(aggregate).putShort(32, (short) (flight.length + battery.length));
        System.arraycopy(flight, 0, aggregate, 34, flight.length);
        System.arraycopy(battery, 0, aggregate, 34 + flight.length, battery.length);
        protocol.receive(aggregate);
        check(test.flightSamples == 0 && !protocol.accepted());

        byte[] accept = datagram(0, 15);
        accept[8] = 1;
        protocol.receive(accept);
        check(protocol.accepted());
        byte[] bad = aggregate.clone();
        bad[7] ^= 1;
        protocol.receive(bad);
        check(test.flightSamples == 0);
        protocol.receive(aggregate);
        check(test.flightSamples == 1 && test.batterySamples == 1);
        aggregate[32]--;
        protocol.receive(aggregate);
        check(test.flightSamples == 1 && test.batterySamples == 1);
        check(protocol.nextIdentityReport("0123456789abcdef012", 12345, 12) == null);
        testIdentityReports(test);
        System.out.println("Telemetry transport, OSD, and identity report checks passed");
    }

    private static void testIdentityReports(TelemetryProtocolTest observer) {
        var protocol = new TelemetryProtocol(0x4321, 0x1238, observer);
        byte[] accept = datagram(0, 15);
        accept[8] = 1;
        accept[9] = 2;
        protocol.receive(accept);
        String identity = "0123456789abcdef0123456789abcdef";
        check(hex(protocol.nextIdentityReport(identity, 12345, 12)).equals(
            "47802143000004a138123812000000003812381200000000381238120000000025005525048402070c004007930139300030313233343536373839616263646566303132009e5b"));
        check(protocol.nextIdentityReport(identity, 12345, 13) == null);
        byte[] body = new byte[31];
        body[0] = 1;
        body[3] = 2;
        byte[] push = PairingExchange.frame(7, 2, 0, 0, 0x94, body);
        byte[] aggregate = datagram(1, 34 + push.length);
        buffer(aggregate).putShort(32, (short) push.length);
        System.arraycopy(push, 0, aggregate, 34, push.length);
        protocol.receive(aggregate);
        check(protocol.nextIdentityReport(identity, 12345, 13) != null);
        check(hex(protocol.nextIdentityReport(identity, 12345, 14)).equals(
            "4e802143000004a83812381200000000381238120000000038123812000000002c00552c0436021b0e0040079301393004000000303132333435363738396162636465663031320000000000b825"));
        protocol.receive(aggregate);
        check(protocol.nextIdentityReport(identity, 12345, 15) == null);
    }

    private static byte[] osd(int command, byte[] payload) {
        byte[] frame = PairingExchange.frame(3, 2, 0, 0, command, payload);
        frame[9] = 3;
        buffer(frame).putShort(frame.length - 2,
            (short) PairingExchange.crc(frame, frame.length - 2, 0x3692, 0x8408));
        return frame;
    }

    private static byte[] datagram(int type, int length) {
        byte[] bytes = new byte[length];
        buffer(bytes).putShort((short) (0x8000 | length)).putShort((short) 0x4321)
            .putShort((short) 0).put((byte) type);
        for (int i = 0; i < 7; i++) bytes[7] ^= bytes[i];
        return bytes;
    }

    private static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String hex(byte[] data) { return HexFormat.of().formatHex(data); }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected telemetry protocol result");
    }
}
