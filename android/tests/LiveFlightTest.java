package dev.freeneo.app;

import static dev.freeneo.app.FlightCommand.Action.*;

public final class LiveFlightTest {
    public static void main(String[] args) {
        var flight = new LiveFlight();
        check(!flight.controls(true, 0).takeoff());
        flight.observe(false, false, 100);
        check(flight.begin(TAKEOFF, 1, false, 101) == null);
        check(flight.begin(TAKEOFF, 1, true, 102) != null);
        check(flight.begin(TAKEOFF, 2, true, 103) == null);
        flight.reply(reply(2, 0));
        check(!flight.controls(true, 104).land());
        flight.reply(reply(1, 0));
        flight.observe(true, true, 200);
        flight.confirmation(true, 201);
        check(flight.begin(LAND, 2, true, 202) != null);
        flight.reply(reply(2, 0));
        check(!flight.controls(true, 203).confirm());
        flight.confirmation(true, 210);
        check(flight.controls(true, 211).confirm());
        check(!flight.controls(true, 711).confirm());
        flight.observe(true, true, 800);
        flight.confirmation(true, 801);
        check(flight.begin(CONFIRM_LANDING, 3, true, 802) != null);
        flight.reply(reply(3, 0));
        check(flight.message().contains("acknowledged"));
        check(flight.begin(CONFIRM_LANDING, 4, true, 803) == null);
        flight.observe(false, false, 900);
        check(flight.message().contains("grounded"));
        check(flight.controls(true, 901).takeoff());
        var withoutAck = new LiveFlight();
        withoutAck.observe(false, false, 100);
        check(withoutAck.begin(TAKEOFF, 1, true, 101) != null);
        withoutAck.observe(true, true, 200);
        check(withoutAck.controls(true, 201).land());
        check(!withoutAck.controls(true, 1201).land());
        System.out.println("Live flight state checks passed");
    }

    private static byte[] reply(int sequence, int status) {
        return PairingExchange.frame(3, 2, sequence, 0x80, 3, 0x2a, new byte[] {(byte) status});
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected live flight state");
    }
}
