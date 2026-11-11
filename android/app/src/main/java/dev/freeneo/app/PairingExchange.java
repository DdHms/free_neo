package dev.freeneo.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The four measured Neo 2 BLE setup requests. No flight command API. */
final class PairingExchange {
    interface Listener {
        void write(byte[] frame);
        void confirmationRequired();
        void credentials(byte[] bssid, byte[] ssid, byte[] passphrase);
    }

    private static final int[] COMMANDS = {0x45, 0x0c, 0x07, 0x0e};
    private final Listener listener;
    private final byte[] identification;
    private final byte[] input = new byte[1536];
    private int used, stage, sequence, receivedBytes;
    private boolean identificationReply, done;
    private Integer decision;
    private byte[] bssid, ssid;

    PairingExchange(String identity, int firstSequence, Listener listener) {
        identification = (" " + identity + "\u0007DJI Fly").getBytes(StandardCharsets.US_ASCII);
        if (identification.length != 41) throw new IllegalArgumentException("Invalid client identity");
        sequence = firstSequence & 0xffff;
        this.listener = listener;
    }

    void start() {
        listener.write(frame(2, 7, sequence, 0x40, COMMANDS[0], identification));
    }

    void receive(byte[] chunk) {
        if (done) return;
        receivedBytes += chunk.length;
        if (chunk.length > 512 || receivedBytes > 65536 || used + chunk.length > input.length) {
            throw new IllegalArgumentException("Bluetooth setup exceeded its data limit");
        }
        System.arraycopy(chunk, 0, input, used, chunk.length);
        used += chunk.length;
        while (used >= 4 && !done) {
            int length = (u(input[1]) | u(input[2]) << 8) & 1023;
            if (u(input[0]) != 0x55 || u(input[3]) != crc(input, 3, 0x77, 0x8c) || length < 13) {
                consume(1);
                continue;
            }
            if (used < length) return;
            byte[] packet = Arrays.copyOf(input, length);
            consume(length);
            if (word(packet, length - 2) == crc(packet, length - 2, 0x3692, 0x8408)) handle(packet);
        }
    }

    private void consume(int length) {
        used -= length;
        System.arraycopy(input, length, input, 0, used);
    }

    private void handle(byte[] packet) {
        if ((u(packet[2]) >> 2) != 1 || u(packet[4]) != 7 || u(packet[5]) != 2 || u(packet[9]) != 7) return;
        int command = u(packet[10]);
        int replySequence = word(packet, 6);
        byte[] payload = Arrays.copyOfRange(packet, 11, packet.length - 2);
        if (command == 0x46) {
            listener.write(frame(2, 7, replySequence, 0xc0, command, new byte[] {0}));
            if (stage == 0 && decision == null) {
                if (payload.length == 0) throw new IllegalArgumentException("Invalid confirmation reply");
                decision = u(payload[0]);
                advanceAfterConfirmation();
            }
            return;
        }
        if (replySequence != sequence || u(packet[8]) != 0xc0 || command != COMMANDS[stage]) return;
        if (payload.length == 0 || payload[0] != 0) {
            throw new IllegalArgumentException("Drone rejected Bluetooth setup");
        }
        switch (stage) {
            case 0 -> {
                if (payload.length != 2 || (payload[1] != 1 && payload[1] != 2)) {
                    throw new IllegalArgumentException("Drone did not accept the identification request");
                }
                if (identificationReply) return;
                identificationReply = true;
                if (payload[1] == 1 && decision == null) decision = 1;
                if (decision == null) listener.confirmationRequired();
                advanceAfterConfirmation();
            }
            case 1 -> {
                if (payload.length != 7) throw new IllegalArgumentException("Invalid Wi-Fi hardware address");
                bssid = Arrays.copyOfRange(payload, 1, 7);
                next();
            }
            case 2 -> {
                ssid = counted(payload, 32);
                next();
            }
            case 3 -> {
                byte[] passphrase = counted(payload, 63);
                done = true;
                listener.credentials(bssid, ssid, passphrase);
            }
        }
    }

    private void advanceAfterConfirmation() {
        if (!identificationReply || decision == null) return;
        if (decision != 1) throw new IllegalArgumentException("Drone pairing was declined or timed out");
        next();
    }

    private void next() {
        sequence = (sequence + 1) & 0xffff;
        listener.write(frame(2, 7, sequence, 0x40, COMMANDS[++stage], new byte[0]));
    }

    private static byte[] counted(byte[] payload, int maximum) {
        if (payload.length < 3 || payload.length - 2 != u(payload[1]) || u(payload[1]) > maximum) {
            throw new IllegalArgumentException("Invalid Wi-Fi credential reply");
        }
        return Arrays.copyOfRange(payload, 2, payload.length);
    }

    static byte[] frame(int sender, int receiver, int sequence, int type, int command, byte[] payload) {
        byte[] frame = new byte[13 + payload.length];
        frame[0] = 0x55;
        frame[1] = (byte) frame.length;
        frame[2] = (byte) (4 | frame.length >> 8);
        frame[3] = (byte) crc(frame, 3, 0x77, 0x8c);
        frame[4] = (byte) sender;
        frame[5] = (byte) receiver;
        frame[6] = (byte) sequence;
        frame[7] = (byte) (sequence >> 8);
        frame[8] = (byte) type;
        frame[9] = 7;
        frame[10] = (byte) command;
        System.arraycopy(payload, 0, frame, 11, payload.length);
        int checksum = crc(frame, frame.length - 2, 0x3692, 0x8408);
        frame[frame.length - 2] = (byte) checksum;
        frame[frame.length - 1] = (byte) (checksum >> 8);
        return frame;
    }

    private static int crc(byte[] data, int length, int value, int polynomial) {
        for (int i = 0; i < length; i++) {
            value ^= u(data[i]);
            for (int bit = 0; bit < 8; bit++) {
                value = (value >>> 1) ^ ((value & 1) == 0 ? 0 : polynomial);
            }
        }
        return value;
    }

    private static int word(byte[] data, int i) { return u(data[i]) | (u(data[i + 1]) << 8); }
    private static int u(byte value) { return value & 0xff; }
}
