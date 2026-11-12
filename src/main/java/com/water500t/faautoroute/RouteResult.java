package com.water500t.faautoroute;

import java.util.List;
import net.minecraft.network.chat.Component;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer;

/**
 * A completed route plus the metadata the GUI needs to preview it.
 */
public record RouteResult(
        FlightPlanComputer.FlightPlan plan,
        int waypointCount,
        int cruiseAltitude,
        int distance,
        int estimatedSeconds,
        int departureElevation,
        int arrivalElevation,
        boolean autoLand,
        boolean deferredLanding,
        int unknownTerrainSamples,
        List<Component> warnings
) {
    public RouteResult {
        warnings = List.copyOf(warnings);
    }

    public List<FlightPlanComputer.EnrouteWaypoint> waypoints() {
        return plan.getEnroute();
    }
}
