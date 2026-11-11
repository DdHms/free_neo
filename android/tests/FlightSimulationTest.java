package dev.freeneo.app;

public final class FlightSimulationTest {
    public static void main(String[] args) {
        FlightSimulation simulation = new FlightSimulation();
        check(!simulation.takeOff() && !simulation.arm() && !simulation.land());
        check(simulation.connect() && !simulation.arm());
        check(simulation.connected() && !simulation.takeOff());
        check(simulation.arm() && simulation.takeOff());
        check(simulation.land() && simulation.state() == FlightSimulation.State.GROUNDED);
        check(!simulation.takeOff() && !simulation.land());
        simulation.reset();
        check(simulation.connect());
        simulation.reset();
        check(!simulation.connected() && simulation.state() == FlightSimulation.State.DISCONNECTED);
        System.out.println("Flight simulation transitions passed");
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected simulator transition");
    }
}
