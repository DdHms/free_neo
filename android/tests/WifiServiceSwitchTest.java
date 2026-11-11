package dev.freeneo.app;

public final class WifiServiceSwitchTest {
    public static void main(String[] args) {
        byte[] local = new byte[23], peer = new byte[23];
        local[0] = 4;
        peer[2] = 36;
        var service = new WifiServiceSwitch(local, peer, 123);
        send(service, WifiServiceSwitch.Kind.TRIGGER, 1);
        byte[] prepare = body(peer, local, 49);
        prepare[46] = 5;
        prepare[47] = prepare[48] = 4;
        service.receive(frame(8, 100, 0x40, prepare), 20);
        send(service, WifiServiceSwitch.Kind.PREPARE_ACK, 21);
        send(service, WifiServiceSwitch.Kind.COMPLETION, 22);
        service.receive(frame(6, 124, 0x80, body(local, null, 24)), 23);
        check(service.state(23) == WifiServiceSwitch.State.WAITING);
        service.receive(frame(6, 101, 0x40, body(peer, local, 51)), 24);
        check(service.state(24) == WifiServiceSwitch.State.WAITING);
        send(service, WifiServiceSwitch.Kind.RESULT_ACK, 25);
        check(service.state(25) == WifiServiceSwitch.State.COMMITTED);
        service.receive(frame(6, 102, 0x40, body(peer, local, 51)), 26);
        send(service, WifiServiceSwitch.Kind.RESULT_ACK, 27);

        var timeout = new WifiServiceSwitch(local, peer, 123);
        send(timeout, WifiServiceSwitch.Kind.TRIGGER, 1);
        check(timeout.state(3001) == WifiServiceSwitch.State.FAILED);
        check(timeout.next(3001) == null);
        System.out.println("Bilateral service activation and timeout checks passed");
    }

    private static void send(WifiServiceSwitch service, WifiServiceSwitch.Kind kind, long now) {
        var packet = service.next(now);
        check(packet != null && packet.kind() == kind);
        service.sent(packet, now);
    }

    private static byte[] frame(int command, int sequence, int type, byte[] body) {
        return PairingExchange.frame(0xe9, 0xee, sequence, type, 0x51, command, body);
    }

    private static byte[] body(byte[] producer, byte[] target, int length) {
        byte[] body = new byte[length];
        System.arraycopy(producer, 0, body, 0, 23);
        if (target != null) System.arraycopy(target, 0, body, 23, 23);
        return body;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected service activation state");
    }
}
