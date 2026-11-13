package dev.freeneo.app;

import java.util.Arrays;

/** Bilateral activation exchange. A transport acknowledgement cannot commit this service. */
final class WifiServiceSwitch {
    enum State { IDLE, WAITING, COMMITTED, FAILED }
    enum Kind { TRIGGER, PREPARE_ACK, COMPLETION, RESULT_ACK }
    record Packet(Kind kind, byte[] frame) {}

    private final byte[] local, peer;
    private final byte[] trigger, completion;
    private final int completionSequence;
    private State state = State.IDLE;
    private Packet outstanding;
    private byte[] prepareAck, resultAck, peerResult;
    private boolean prepared, completionSent, completionAccepted, resultAcknowledged;
    private long deadline, retryAt, committedAt;

    WifiServiceSwitch(byte[] local, byte[] peer, int sequence) {
        if (local.length != 23 || peer.length != 23) throw new IllegalArgumentException("Invalid service identity");
        this.local = local.clone();
        this.peer = peer.clone();
        completionSequence = (sequence + 1) & 0xffff;
        trigger = request(2, sequence, new byte[] {5, 4, 4, 0, 0, 0, 2, 0, 2, 0, 0});
        byte[] body = new byte[51];
        System.arraycopy(local, 0, body, 0, 23);
        System.arraycopy(peer, 0, body, 23, 23);
        completion = request(6, completionSequence, body);
    }

    State state(long now) {
        if (state == State.WAITING && now >= deadline) fail();
        return state;
    }

    void receive(byte[] inner, long now) {
        State current = state(now);
        if ((current != State.WAITING && current != State.COMMITTED)
                || inner[4] != (byte) 0xe9 || inner[5] != (byte) 0xee || inner[9] != 0x51) return;
        int sequence = (inner[6] & 255) | (inner[7] & 255) << 8;
        byte[] body = Arrays.copyOfRange(inner, 11, inner.length - 2);
        int type = inner[8] & 255;
        if (inner[10] == 8 && type == 0x40 && current == State.WAITING
                && body.length == 49 && identities(body)
                && body[46] == 5 && body[47] == 4 && body[48] == 4) {
            prepareAck = ack(8, sequence, (byte) 0);
        } else if (inner[10] == 6 && type == 0x40 && body.length == 51 && identities(body)) {
            if (current == State.COMMITTED) {
                if (now - committedAt < 3_000 && Arrays.equals(body, peerResult)) {
                    resultAck = ack(6, sequence, body[46]);
                }
            } else if (peerResult != null && !Arrays.equals(body, peerResult)) {
                fail();
            } else {
                peerResult = body;
                resultAcknowledged = false;
                resultAck = ack(6, sequence, body[46]);
            }
        } else if (inner[10] == 6 && (type == 0x80 || type == 0xc0)
                && current == State.WAITING && completionSent && sequence == completionSequence
                && body.length == 24 && matches(body, 0, local)) {
            if (body[23] != 0) fail();
            else {
                completionAccepted = true;
                commitIfReady(now);
            }
        }
    }

    Packet next(long now) {
        State current = state(now);
        if (outstanding != null || current == State.FAILED) return null;
        if (current == State.COMMITTED) {
            if (resultAck != null && now - committedAt < 3_000) return takeResultAck();
            return null;
        }
        if (resultAck != null && peerResult[46] != 0) return takeResultAck();
        if (prepareAck != null) {
            outstanding = new Packet(Kind.PREPARE_ACK, prepareAck);
            prepareAck = null;
        } else if (prepared) {
            if (!completionSent || !completionAccepted && now >= retryAt) {
                outstanding = new Packet(Kind.COMPLETION, completion);
            } else if (resultAck != null) return takeResultAck();
        } else if (now >= retryAt) {
            outstanding = new Packet(Kind.TRIGGER, trigger);
        }
        return outstanding;
    }

    void sent(Packet packet, long now) {
        if (packet != outstanding) throw new IllegalStateException("Unmatched service transmission");
        State current = state(now);
        outstanding = null;
        if (current == State.FAILED || current == State.COMMITTED) return;
        switch (packet.kind()) {
            case TRIGGER -> {
                if (state == State.IDLE) {
                    state = State.WAITING;
                    deadline = now + 3_000;
                }
                retryAt = now + 400;
            }
            case PREPARE_ACK -> prepared = true;
            case COMPLETION -> {
                completionSent = true;
                retryAt = now + 200;
            }
            case RESULT_ACK -> {
                if (peerResult[46] != 0) fail();
                else resultAcknowledged = true;
            }
        }
        commitIfReady(now);
    }

    private Packet takeResultAck() {
        outstanding = new Packet(Kind.RESULT_ACK, resultAck);
        resultAck = null;
        return outstanding;
    }

    private void commitIfReady(long now) {
        if (state == State.WAITING && prepared && completionAccepted && resultAcknowledged) {
            state = State.COMMITTED;
            committedAt = now;
        }
    }

    private void fail() {
        state = State.FAILED;
        prepareAck = resultAck = null;
    }

    private boolean identities(byte[] body) { return matches(body, 0, peer) && matches(body, 23, local); }

    private static boolean matches(byte[] body, int offset, byte[] identity) {
        for (int i = 0; i < identity.length; i++) {
            if (body[offset + i] != identity[i]) return false;
        }
        return true;
    }

    private byte[] ack(int command, int sequence, byte status) {
        byte[] body = Arrays.copyOf(peer, 24);
        body[23] = status;
        return PairingExchange.frame(0xee, 0xe9, sequence, 0xc0, 0x51, command, body);
    }

    private static byte[] request(int command, int sequence, byte[] body) {
        return PairingExchange.frame(0xee, 0xe9, sequence, 0x40, 0x51, command, body);
    }
}
