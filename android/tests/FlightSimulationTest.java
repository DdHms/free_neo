package dev.freeneo.app;

public final class FlightSimulationTest {
    public static void main(String[] args) {
        FlightSimulation simulation = new FlightSimulation();
        check(!simulation.takeOff() && !simulation.arm() && !simulation.land());
        check(simulation.connect() && !simulation.arm());
        check(simulation.connected(0) && !simulation.takeOff());
        check(simulation.arm() && simulation.takeOff());
        check(simulation.land() && simulation.state() == FlightSimulation.State.LANDING);
        check(simulation.requestFinalConfirmation() && !simulation.observeLanding(false, false));
        check(simulation.confirmLanding() && !simulation.observeLanding(false, true));
        check(simulation.observeLanding(false, false) && simulation.state() == FlightSimulation.State.GROUNDED);
        check(!simulation.takeOff() && !simulation.land());
        check(simulation.arm() && simulation.takeOff());
        simulation.heartbeat(500);
        simulation.checkLink(1999);
        check(simulation.state() == FlightSimulation.State.AIRBORNE);
        simulation.checkLink(2000);
        check(simulation.state() == FlightSimulation.State.LINK_LOST);
        check(!simulation.land() && !simulation.confirmLanding() && !simulation.observeLanding(false, false));
        simulation.reset();
        check(simulation.connect());
        simulation.reset();
        check(!simulation.connected(0) && simulation.state() == FlightSimulation.State.DISCONNECTED);
        System.out.println("Flight simulation transitions passed");
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected simulator transition");
    }
}
