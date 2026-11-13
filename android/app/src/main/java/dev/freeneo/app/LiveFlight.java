package dev.freeneo.app;

/** Command admission and outcome tracking, owned by the UDP worker. */
final class LiveFlight {
    record Controls(boolean takeoff, boolean land, boolean confirm) {
        static final Controls DISABLED = new Controls(false, false, false);
    }

    private long flightAt = -10_000, confirmationAt = -10_000, landAt = -1, sentAt;
    private boolean flying, motors, needsConfirmation, confirmationSent;
    private boolean takeoffAttempted, airborneObserved;
    private FlightCommand pending;
    private String message = "Waiting for flight service and fresh aircraft telemetry.";

    void observe(boolean flying, boolean motors, long now) {
        this.flying = flying;
        this.motors = motors;
        flightAt = now;
        if (flying && motors) {
            airborneObserved = true;
            if (landAt < 0) message = "Aircraft reports airborne.";
        } else if (!flying && !motors && airborneObserved) {
            message = "Aircraft reports grounded with motors off.";
            landAt = -1;
            confirmationSent = false;
            airborneObserved = false;
            takeoffAttempted = false;
            pending = null;
        }
    }

    void confirmation(boolean needed, long now) {
        needsConfirmation = needed;
        confirmationAt = now;
    }

    Controls controls(boolean ready, long now) {
        if (!ready || now - flightAt > 1000) return Controls.DISABLED;
        if (pending != null && now - sentAt >= 1500) {
            message = "No command acknowledgement. Check aircraft state.";
            pending = null;
        }
        if (pending != null) return Controls.DISABLED;
        return new Controls(!flying && !motors && !takeoffAttempted && landAt < 0,
            flying && motors && landAt < 0,
            motors && now - flightAt <= 500 && landAt >= 0 && !confirmationSent && needsConfirmation
                && confirmationAt > landAt && now - confirmationAt <= 500);
    }

    FlightCommand begin(FlightCommand.Action action, int sequence, boolean ready, long now) {
        Controls allowed = controls(ready, now);
        boolean permitted = switch (action) {
            case TAKEOFF -> allowed.takeoff();
            case LAND -> allowed.land();
            case CONFIRM_LANDING -> allowed.confirm();
        };
        if (!permitted) return null;
        switch (action) {
            case TAKEOFF -> takeoffAttempted = true;
            case LAND -> landAt = now;
            case CONFIRM_LANDING -> confirmationSent = true;
        }
        sentAt = now;
        pending = new FlightCommand(action, sequence);
        message = action + " sent. Waiting for aircraft acknowledgement.";
        return pending;
    }

    void reply(byte[] frame) {
        if (pending == null) return;
        pending.acknowledge(frame).ifPresent(ack -> {
            if (ack.accepted()) message = pending.action() + " acknowledged. Waiting for aircraft telemetry.";
            else {
                message = pending.action() + " rejected (status " + ack.status() + ").";
                switch (pending.action()) {
                    case TAKEOFF -> takeoffAttempted = false;
                    case LAND -> landAt = -1;
                    case CONFIRM_LANDING -> confirmationSent = false;
                }
            }
            pending = null;
        });
    }

    String message() { return message; }
}
