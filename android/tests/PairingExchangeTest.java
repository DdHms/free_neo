package dev.freeneo.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

public final class PairingExchangeTest implements PairingExchange.Listener {
    private final List<byte[]> writes = new ArrayList<>();
    private boolean prompt, credentials;

    @Override public void write(byte[] frame) { writes.add(frame); }
    @Override public void confirmationRequired() { prompt = true; }
    @Override public void credentials(byte[] bssid, byte[] ssid, byte[] passphrase) {
        check(Arrays.equals(bssid, new byte[] {2, 1, 2, 3, 4, 5}));
        check(Arrays.equals(ssid, new byte[] {'N', 'E', 'O'}));
        check(new String(passphrase, StandardCharsets.US_ASCII).equals("testpass"));
        credentials = true;
    }

    public static void main(String[] args) {
        var test = new PairingExchangeTest();
        var exchange = new PairingExchange("0123456789abcdef0123456789abcdef", 65535, test);
        exchange.start();
        // Generated from the pinned Python client's encoder.
        check(HexFormat.of().formatHex(test.writes.get(0)).equals(
            "5536043d0207ffff40074520303132333435363738396162636465663031323334353637383961626364656607444a4920466c79ce69"));
        byte[] reply = reply(0x45, 65535, 0, 2);
        byte[] corrupt = reply.clone();
        corrupt[12] ^= 1;
        exchange.receive(corrupt);
        check(!test.prompt);
        exchange.receive(Arrays.copyOf(reply, 7));
        exchange.receive(Arrays.copyOfRange(reply, 7, reply.length));
        check(test.prompt && test.writes.size() == 1);
        exchange.receive(reply(0x46, 44, 1));
        check(test.writes.size() == 3 && test.writes.get(1)[10] == 0x46 && test.writes.get(2)[10] == 0x0c);
        exchange.receive(reply(0x0c, 4, 0, 2, 1, 2, 3, 4, 5));
        check(test.writes.size() == 3);
        exchange.receive(reply(0x0c, 0, 0, 2, 1, 2, 3, 4, 5));
        exchange.receive(reply(0x07, 1, 0, 3, 'N', 'E', 'O'));
        exchange.receive(reply(0x0e, 2, 0, 8, 't', 'e', 's', 't', 'p', 'a', 's', 's'));
        check(test.credentials && test.writes.size() == 5);

        var denied = new PairingExchangeTest();
        exchange = new PairingExchange("0123456789abcdef0123456789abcdef", 1, denied);
        exchange.start();
        exchange.receive(reply(0x45, 1, 0, 2));
        try {
            exchange.receive(reply(0x46, 44, 0));
            throw new AssertionError("Rejected pairing was accepted");
        } catch (IllegalArgumentException expected) {
            check(denied.writes.size() == 2 && !denied.credentials);
        }
        System.out.println("Pairing transcript checks passed");
    }

    private static byte[] reply(int command, int sequence, int... values) {
        byte[] payload = new byte[values.length];
        for (int i = 0; i < values.length; i++) payload[i] = (byte) values[i];
        return PairingExchange.frame(7, 2, sequence, 0xc0, command, payload);
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected pairing exchange");
    }
}
