package dev.freeneo.app;

final class FlightSimulation {
    enum State { DISCONNECTED, CONNECTING, GROUNDED, ARMED, AIRBORNE }
    private State state = State.DISCONNECTED;

    State state() { return state; }
    boolean connect() { return transition(State.DISCONNECTED, State.CONNECTING); }
    boolean connected() { return transition(State.CONNECTING, State.GROUNDED); }
    boolean arm() { return transition(State.GROUNDED, State.ARMED); }
    boolean takeOff() { return transition(State.ARMED, State.AIRBORNE); }
    boolean land() { return transition(State.AIRBORNE, State.GROUNDED); }
    void reset() { state = State.DISCONNECTED; }

    private boolean transition(State from, State to) {
        if (state != from) return false;
        state = to;
        return true;
    }
}
