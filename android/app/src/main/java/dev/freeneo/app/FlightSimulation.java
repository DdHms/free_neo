package dev.freeneo.app;

final class FlightSimulation {
    enum State {
        DISCONNECTED, CONNECTING, GROUNDED, ARMED, AIRBORNE,
        LANDING, AWAITING_CONFIRMATION, LINK_LOST
    }

    static final long LINK_TIMEOUT_MS = 1_500;
    private State state = State.DISCONNECTED;
    private long lastHeartbeat;

    State state() { return state; }
    boolean connect() { return transition(State.DISCONNECTED, State.CONNECTING); }

    boolean connected(long now) {
        if (!transition(State.CONNECTING, State.GROUNDED)) return false;
        heartbeat(now);
        return true;
    }

    void heartbeat(long now) {
        if (state != State.LINK_LOST) lastHeartbeat = now;
    }

    void checkLink(long now) {
        if (state != State.DISCONNECTED && state != State.CONNECTING
                && now - lastHeartbeat >= LINK_TIMEOUT_MS) state = State.LINK_LOST;
    }

    boolean arm() { return transition(State.GROUNDED, State.ARMED); }
    boolean takeOff() { return transition(State.ARMED, State.AIRBORNE); }
    boolean land() { return transition(State.AIRBORNE, State.LANDING); }

    boolean requestFinalConfirmation() {
        return transition(State.LANDING, State.AWAITING_CONFIRMATION);
    }

    boolean confirmLanding() { return transition(State.AWAITING_CONFIRMATION, State.LANDING); }

    boolean observeLanding(boolean flying, boolean motorsOn) {
        return !flying && !motorsOn && transition(State.LANDING, State.GROUNDED);
    }

    void reset() { state = State.DISCONNECTED; }

    private boolean transition(State from, State to) {
        if (state != from) return false;
        state = to;
        return true;
    }
}
