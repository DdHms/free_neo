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
        System.out.println("Telemetry transport and OSD checks passed");
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
