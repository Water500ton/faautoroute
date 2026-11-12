package com.water500t.faautoroute;

import java.util.List;

/**
 * User-facing options for one automatic route.
 *
 * @param destX          destination block X
 * @param destZ          destination block Z
 * @param cruiseAltitude requested cruise altitude, or {@code null} for automatic terrain-based selection
 * @param speed          managed speed in blocks/second (0 = leave speed to the pilot)
 * @param autoLand       whether to build a full arrival/landing plan
 * @param onGround       whether the player is currently standing on the ground
 * @param viaPoints      ordered points to fly through before the destination
 */
public record RouteOptions(
        int destX,
        int destZ,
        Integer cruiseAltitude,
        int speed,
        boolean autoLand,
        boolean onGround,
        List<RoutePoint> viaPoints
) {
    public RouteOptions {
        speed = Math.max(0, Math.min(30, speed));
        viaPoints = List.copyOf(viaPoints);
    }

    public RouteOptions(int destX, int destZ, Integer cruiseAltitude, int speed, boolean autoLand, boolean onGround) {
        this(destX, destZ, cruiseAltitude, speed, autoLand, onGround, List.of());
    }
}
