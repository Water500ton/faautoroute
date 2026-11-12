package com.water500t.faautoroute;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer;

/**
 * Terrain-aware flight planner for FlightAssistant.
 *
 * <p>The planner samples loaded chunks in a corridor around every leg of the
 * route, picks a safe cruise altitude, and emits a plan with climb, cruise,
 * turn, top-of-descent, final-approach and (optionally) arrival data.</p>
 */
public final class RoutePlanner {
    public static final int MAX_DISTANCE = 16_000;
    public static final int DEFAULT_SPEED = 15;
    public static final int TERRAIN_CLEARANCE = 50;
    public static final int CORRIDOR_RADIUS = 48;
    public static final int FINAL_CORRIDOR_RADIUS = 16;
    /** Highest ground above the aim elevation that a final approach tolerates. */
    private static final int APPROACH_OBSTRUCTION = 8;

    private static final int SAMPLE_STEP = 24;
    private static final int CRUISE_WAYPOINT_SPACING = 512;
    private static final int HOLD_POINTS = 4;
    private static final double HOLD_RADIUS = 240.0;

    private RoutePlanner() {
    }

    public static RouteResult plan(Level level, Vec3 start, RouteOptions options) throws RoutePlanningException {
        int startX = Mth.floor(start.x);
        int startY = Mth.floor(start.y);
        int startZ = Mth.floor(start.z);

        List<RoutePoint> path = buildPath(startX, startZ, options);
        double distance = pathDistance(path);

        if (distance < 48.0) {
            throw new RoutePlanningException(Component.translatable("error.faautoroute.too_close"));
        }
        if (distance > MAX_DISTANCE) {
            throw new RoutePlanningException(Component.translatable("error.faautoroute.too_far", MAX_DISTANCE));
        }
        if (options.autoLand() && distance < 48.0) {
            throw new RoutePlanningException(Component.translatable("error.faautoroute.auto_land_too_close"));
        }

        List<Component> warnings = new ArrayList<>();
        TerrainScan scan = scanPath(level, path, CORRIDOR_RADIUS);
        int fallbackTerrain = level.dimension().equals(Level.NETHER) ? 100 : level.getSeaLevel() + 100;
        int terrainMax = Math.max(scan.maxHeight(), scan.unknownSamples() > 0 ? fallbackTerrain : level.getMinBuildHeight());

        if (scan.unknownSamples() > 0) {
            warnings.add(Component.translatable("warning.faautoroute.unloaded_samples", scan.unknownSamples()));
        }

        int minFlightY = level.getMinBuildHeight() + 8;
        int maxFlightY = maxFlightY(level);
        if (level.dimension().equals(Level.NETHER)) {
            warnings.add(Component.translatable("warning.faautoroute.nether_ceiling"));
        }

        if (terrainMax + 8 > maxFlightY) {
            throw new RoutePlanningException(Component.translatable("error.faautoroute.terrain_too_high", terrainMax, maxFlightY));
        }

        int clearance = Math.max(8, Math.min(TERRAIN_CLEARANCE, maxFlightY - terrainMax - 6));
        int safeCruise = Math.max(terrainMax + clearance, level.getSeaLevel() + 50);
        if (!options.onGround()) {
            safeCruise = Math.max(safeCruise, startY + 10);
        }
        safeCruise = Mth.clamp(safeCruise, minFlightY, maxFlightY);

        int cruise = safeCruise;
        if (options.cruiseAltitude() != null) {
            int requested = options.cruiseAltitude();
            if (requested < safeCruise) {
                warnings.add(Component.translatable("warning.faautoroute.cruise_raised", requested, safeCruise));
            } else if (requested > maxFlightY) {
                warnings.add(Component.translatable("warning.faautoroute.cruise_lowered", requested, maxFlightY));
            }
            cruise = Mth.clamp(requested, safeCruise, maxFlightY);
        }

        boolean autoLand = options.autoLand();
        boolean deferredLanding = false;
        boolean holdingDescent = false;
        int arrivalElevation = 0;
        if (autoLand) {
            LandingCheck landing = checkLanding(level, options.destX(), options.destZ());
            if (!landing.safe()) {
                if (landing.unloaded()) {
                    deferredLanding = true;
                    autoLand = false;
                    warnings.add(Component.translatable("warning.faautoroute.landing_deferred"));
                } else {
                    throw new RoutePlanningException(landing.message());
                }
            } else {
                arrivalElevation = landing.height();
            }
        }

        List<Leg> legs = buildLegs(path);
        if (legs.isEmpty()) {
            throw new RoutePlanningException(Component.translatable("error.faautoroute.no_waypoints"));
        }

        boolean startWaypoint = options.viaPoints().stream()
                .anyMatch(point -> distance(point, new RoutePoint(startX, startZ)) < 8.0);

        if (autoLand && !legs.isEmpty()) {
            Leg lastLeg = legs.get(legs.size() - 1);
            double finalDistance = finalApproachDistance(lastLeg.length);
            double approachDistance = Math.max(24.0, lastLeg.length - finalDistance);
            int finalEstimate = arrivalElevation + (int) Math.round((lastLeg.length - approachDistance) / 10.0) + 10;
            int maxDescentCruise = finalEstimate + (int) Math.floor(Math.max(64.0, lastLeg.length - approachDistance) / 8.0);
            if (cruise > maxDescentCruise) {
                if (maxDescentCruise >= terrainMax + 8) {
                    warnings.add(Component.translatable("warning.faautoroute.cruise_limited_for_landing", cruise, maxDescentCruise));
                    cruise = maxDescentCruise;
                } else {
                    holdingDescent = true;
                    warnings.add(Component.translatable("warning.faautoroute.holding_descent",
                            maxDescentCruise, terrainMax + 8));
                }
            }
        }

        List<PlannedWaypoint> points = new ArrayList<>();
        if (startWaypoint) {
            addPoint(points, startX, startZ, startY, options.speed(), 0.0, true);
        }
        Leg first = legs.get(0);
        double climbDistance = Math.min(first.length * 0.40, Math.max(96.0, (cruise - startY) * 6.0));
        climbDistance = Math.max(32.0, Math.min(climbDistance, first.length - 32.0));
        if (first.length < 64.0) {
            climbDistance = first.length / 2.0;
        }
        addPoint(points, pointX(first, climbDistance), pointZ(first, climbDistance), cruise, options.speed(), 0.0, true);

        int speed = options.speed();

        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            boolean lastLeg = i == legs.size() - 1;
            double from = i == 0 ? climbDistance : 0.0;

            if (!lastLeg) {
                addCruisePoints(points, leg, from, leg.length, cruise, speed);
                addPoint(points, leg.b.x(), leg.b.z(), cruise, speed, 32.0, true);
                continue;
            }

            if (!autoLand) {
                addCruisePoints(points, leg, from, leg.length, cruise, speed);
                int destinationSpeed = speed == 0 ? 0 : Math.min(speed, 10);
                addPoint(points, options.destX(), options.destZ(), cruise, destinationSpeed, 24.0, true);
                break;
            }

            double finalDistance = finalApproachDistance(leg.length);
            double approachDistance = Math.max(from + 24.0, leg.length - finalDistance);
            approachDistance = Math.min(approachDistance, leg.length - 32.0);
            if (approachDistance <= from + 16.0) {
                throw new RoutePlanningException(Component.translatable("error.faautoroute.auto_land_short_final"));
            }

            TerrainScan finalScan = scanSegment(level, leg, approachDistance, leg.length, FINAL_CORRIDOR_RADIUS);
            int finalAltitude = arrivalElevation + (int) Math.round((leg.length - approachDistance) / 10.0) + 10;
            finalAltitude = Math.max(finalAltitude, finalScan.maxHeight() + 20);
            finalAltitude = Math.min(cruise, Math.max(finalAltitude, arrivalElevation + 12));

            // The landing assist descends towards the aim point's elevation, so
            // terrain standing above that elevation inside the final approach is
            // ground the aircraft would reach before the aim point.
            if (finalScan.maxHeight() > arrivalElevation + APPROACH_OBSTRUCTION) {
                warnings.add(Component.translatable("warning.faautoroute.approach_terrain",
                        finalScan.maxHeight() - arrivalElevation));
            }

            if (holdingDescent) {
                addHoldingApproach(points, level, leg, options.destX(), options.destZ(),
                        cruise, finalAltitude, finalDistance, speed, terrainMax, from);
                break;
            }

            double descentRoom = Math.max(120.0, (cruise - finalAltitude) * 8.0 + 100.0);
            double topOfDescent = approachDistance - descentRoom;
            topOfDescent = Math.max(from + 24.0, topOfDescent);
            if (topOfDescent >= approachDistance - 32.0) {
                topOfDescent = (from + approachDistance) / 2.0;
            }
            if (topOfDescent < from + 16.0) {
                topOfDescent = from;
            }

            addCruisePoints(points, leg, from, topOfDescent, cruise, speed);
            if (topOfDescent > from + 16.0 && topOfDescent < approachDistance - 16.0) {
                addPoint(points, pointX(leg, topOfDescent), pointZ(leg, topOfDescent), cruise, speed, 32.0, true);
            }

            double descentStart = Math.max(topOfDescent, from);
            int segments = Math.max(1, (int) Math.ceil((approachDistance - descentStart) / 220.0));
            for (int j = 1; j < segments; j++) {
                double t = j / (double) segments;
                double d = descentStart + (approachDistance - descentStart) * t;
                if (d <= from + 8.0) {
                    continue;
                }
                double altT = approachDistance - descentStart <= 1.0E-6 ? 1.0 : (d - descentStart) / (approachDistance - descentStart);
                int altitude = (int) Math.round(Mth.lerp(altT, cruise, finalAltitude));
                addPoint(points, pointX(leg, d), pointZ(leg, d), altitude, speed, 64.0, false);
            }

            int approachSpeed = speed == 0 ? 0 : Math.min(speed, 8);
            addPoint(points, pointX(leg, approachDistance), pointZ(leg, approachDistance), finalAltitude, approachSpeed, 24.0, true);
        }

        if (points.isEmpty()) {
            throw new RoutePlanningException(Component.translatable("error.faautoroute.no_waypoints"));
        }

        List<FlightPlanComputer.EnrouteWaypoint> waypoints = toWaypoints(points, startWaypoint);
        FlightPlanComputer.ArrivalData arrival = autoLand
                ? new FlightPlanComputer.ArrivalData(
                        options.destX(),
                        options.destZ(),
                        arrivalElevation,
                        0.0f,
                        Math.min(cruise, arrivalElevation + 20),
                        FlightPlanComputer.ArrivalData.MinimumsType.ABSOLUTE,
                        Math.min(cruise, arrivalElevation + 80),
                        waypoints.size() - 1)
                : new FlightPlanComputer.ArrivalData(
                        0, 0, 0, 0.0f, 0,
                        FlightPlanComputer.ArrivalData.MinimumsType.ABSOLUTE,
                        0, 0);

        return buildResult(startX, startY, startZ, distance, cruise, options, waypoints, arrival, arrivalElevation,
                autoLand, deferredLanding, scan.unknownSamples(), warnings);
    }

    private static List<RoutePoint> buildPath(int startX, int startZ, RouteOptions options) {
        List<RoutePoint> path = new ArrayList<>();
        path.add(new RoutePoint(startX, startZ));
        RoutePoint destination = new RoutePoint(options.destX(), options.destZ());

        for (RoutePoint via : options.viaPoints()) {
            RoutePoint last = path.get(path.size() - 1);
            if (distance(last, via) < 48.0) {
                continue;
            }
            if (distance(via, destination) < 48.0) {
                continue;
            }
            path.add(via);
        }

        while (path.size() > 1 && distance(path.get(path.size() - 1), destination) < 48.0) {
            path.remove(path.size() - 1);
        }
        path.add(destination);
        return path;
    }

    private static List<Leg> buildLegs(List<RoutePoint> path) {
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            RoutePoint a = path.get(i);
            RoutePoint b = path.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            if (length < 1.0) {
                continue;
            }
            legs.add(new Leg(a, b, length, dx / length, dz / length));
        }
        return legs;
    }

    private static double pathDistance(List<RoutePoint> path) {
        double distance = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            distance += distance(path.get(i), path.get(i + 1));
        }
        return distance;
    }

    private static double distance(RoutePoint a, RoutePoint b) {
        return Math.hypot(b.x() - a.x(), b.z() - a.z());
    }

    private static RouteResult buildResult(
            int startX,
            int startY,
            int startZ,
            double distance,
            int cruise,
            RouteOptions options,
            List<FlightPlanComputer.EnrouteWaypoint> waypoints,
            FlightPlanComputer.ArrivalData arrival,
            int arrivalElevation,
            boolean autoLand,
            boolean deferredLanding,
            int unknownSamples,
            List<Component> warnings
    ) {
        FlightPlanComputer.DepartureData departure = new FlightPlanComputer.DepartureData(startX, startZ, startY, 1.0f);
        FlightPlanComputer.FlightPlan plan = new FlightPlanComputer.FlightPlan(departure, waypoints, arrival);
        int speed = options.speed() == 0 ? DEFAULT_SPEED : options.speed();
        int eta = (int) Math.round(distance / speed);
        return new RouteResult(
                plan,
                waypoints.size(),
                cruise,
                (int) Math.round(distance),
                eta,
                startY,
                arrivalElevation,
                autoLand,
                deferredLanding,
                unknownSamples,
                warnings
        );
    }

    private static double finalApproachDistance(double legLength) {
        double distance = Math.min(legLength * 0.45, Math.max(120.0, Math.min(450.0, legLength * 0.25)));
        return Math.max(60.0, distance);
    }

    private static void addHoldingApproach(
            List<PlannedWaypoint> points,
            Level level,
            Leg leg,
            int destX,
            int destZ,
            int cruise,
            int finalAltitude,
            double finalDistance,
            int speed,
            int terrainMax,
            double from
    ) {
        double entryDistance = Math.max(from + 48.0, leg.length - HOLD_RADIUS);
        entryDistance = Math.min(entryDistance, leg.length - 32.0);
        addCruisePoints(points, leg, from, entryDistance, cruise, speed);

        int entryX = pointX(leg, entryDistance);
        int entryZ = pointZ(leg, entryDistance);
        addPoint(points, entryX, entryZ, cruise, speed, 32.0, true);

        double startAngle = Math.atan2(entryZ - destZ, entryX - destX);
        int holdTerrain = scanCircle(level, destX, destZ, HOLD_RADIUS, 16);
        int holdBottom = Math.max(finalAltitude + 20, holdTerrain + 20);
        holdBottom = Math.min(holdBottom, cruise);

        for (int i = 1; i <= HOLD_POINTS; i++) {
            double angle = startAngle + (Math.PI * 2.0 * i / HOLD_POINTS);
            int x = (int) Math.round(destX + Math.cos(angle) * HOLD_RADIUS);
            int z = (int) Math.round(destZ + Math.sin(angle) * HOLD_RADIUS);
            int altitude = (int) Math.round(Mth.lerp(i / (double) HOLD_POINTS, cruise, holdBottom));
            addPoint(points, x, z, altitude, speed, 32.0, true);
        }

        int approachX = (int) Math.round(destX + Math.cos(startAngle) * finalDistance);
        int approachZ = (int) Math.round(destZ + Math.sin(startAngle) * finalDistance);
        int approachSpeed = speed == 0 ? 0 : Math.min(speed, 8);
        addPoint(points, approachX, approachZ, finalAltitude, approachSpeed, 24.0, true);
    }

    private static int scanCircle(Level level, int centerX, int centerZ, double radius, int samples) {
        int max = level.getMinBuildHeight();
        for (int i = 0; i < samples; i++) {
            double angle = Math.PI * 2.0 * i / samples;
            int x = (int) Math.round(centerX + Math.cos(angle) * radius);
            int z = (int) Math.round(centerZ + Math.sin(angle) * radius);
            Integer height = heightAt(level, x, z);
            if (height != null) {
                max = Math.max(max, clampTerrainHeight(level, height));
            }
        }
        return max;
    }

    private static void addCruisePoints(
            List<PlannedWaypoint> points,
            Leg leg,
            double from,
            double to,
            int cruise,
            int speed
    ) {
        if (to <= from + 64.0) {
            return;
        }
        double next = from + CRUISE_WAYPOINT_SPACING;
        while (next < to - 64.0) {
            addPoint(points, pointX(leg, next), pointZ(leg, next), cruise, speed, 64.0, false);
            next += CRUISE_WAYPOINT_SPACING;
        }
    }

    private static void addPoint(
            List<PlannedWaypoint> points,
            int x,
            int z,
            int altitude,
            int speed,
            double minimumGap,
            boolean force
    ) {
        if (!points.isEmpty()) {
            PlannedWaypoint last = points.get(points.size() - 1);
            double gap = Math.hypot(x - last.x(), z - last.z());
            if (gap < (force ? 8.0 : minimumGap)) {
                if (!force) {
                    return;
                }
                while (points.size() > 1) {
                    PlannedWaypoint previous = points.get(points.size() - 1);
                    if (Math.hypot(x - previous.x(), z - previous.z()) >= 8.0) {
                        break;
                    }
                    points.remove(points.size() - 1);
                }
            }
        }
        points.add(new PlannedWaypoint(x, z, altitude, speed));
    }

    private static int pointX(Leg leg, double distance) {
        return (int) Math.round(leg.a.x() + leg.ux * distance);
    }

    private static int pointZ(Leg leg, double distance) {
        return (int) Math.round(leg.a.z() + leg.uz * distance);
    }

    private static List<FlightPlanComputer.EnrouteWaypoint> toWaypoints(List<PlannedWaypoint> points, boolean originFirst) {
        List<FlightPlanComputer.EnrouteWaypoint> waypoints = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            PlannedWaypoint point = points.get(i);
            FlightPlanComputer.EnrouteWaypoint.Active active = null;
            if (originFirst && i == 0) {
                active = points.size() > 1
                        ? FlightPlanComputer.EnrouteWaypoint.Active.ORIGIN
                        : FlightPlanComputer.EnrouteWaypoint.Active.TARGET;
            } else if (i == (originFirst ? 1 : 0)) {
                active = FlightPlanComputer.EnrouteWaypoint.Active.TARGET;
            }
            waypoints.add(new FlightPlanComputer.EnrouteWaypoint(
                    point.x(),
                    point.z(),
                    point.altitude(),
                    point.speed(),
                    active,
                    UUID.randomUUID()
            ));
        }
        return waypoints;
    }

    private static TerrainScan scanPath(Level level, List<RoutePoint> path, int corridor) {
        int max = level.getMinBuildHeight();
        int unknown = 0;
        int samples = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            RoutePoint a = path.get(i);
            RoutePoint b = path.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            if (length < 1.0) {
                continue;
            }
            Leg leg = new Leg(a, b, length, dx / length, dz / length);
            TerrainScan scan = scanSegment(level, leg, 0.0, length, corridor);
            max = Math.max(max, scan.maxHeight());
            unknown += scan.unknownSamples();
            samples += scan.samples();
        }
        return new TerrainScan(max, unknown, samples);
    }

    private static TerrainScan scanSegment(Level level, Leg leg, double from, double to, int corridor) {
        int steps = Math.max(4, (int) Math.ceil((to - from) / 16.0));
        int[] offsets = {-corridor, 0, corridor};
        int max = level.getMinBuildHeight();
        int unknown = 0;
        int samples = 0;

        for (int i = 0; i <= steps; i++) {
            double d = from + (to - from) * (i / (double) steps);
            int cx = pointX(leg, d);
            int cz = pointZ(leg, d);
            for (int offset : offsets) {
                int sx = cx - Mth.floor(leg.uz * offset);
                int sz = cz + Mth.floor(leg.ux * offset);
                Integer height = heightAt(level, sx, sz);
                samples++;
                if (height == null) {
                    unknown++;
                } else {
                    max = Math.max(max, clampTerrainHeight(level, height));
                }
            }
        }
        return new TerrainScan(max, unknown, samples);
    }

    private static int clampTerrainHeight(Level level, int height) {
        if (level.dimension().equals(Level.NETHER) && height > 118) {
            // The Nether roof is bedrock at Y=127 and is not flyable terrain.
            return 100;
        }
        return height;
    }

    public static boolean isChunkLoaded(Level level, int x, int z) {
        return level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    private static Integer heightAt(Level level, int x, int z) {
        if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        int minY = level.getMinBuildHeight();
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (height > minY + 1) {
            return height;
        }

        // Client heightmaps can be temporarily empty even when the chunk is
        // present (for example immediately after a chunk packet is applied).
        // Fall back to a direct column scan so a destination platform is not
        // misclassified as an unloaded destination.
        for (int y = level.getMaxBuildHeight() - 1; y > minY; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && state.getFluidState().isEmpty() && state.blocksMotion()) {
                return y + 1;
            }
        }
        return null;
    }

    private static int maxFlightY(Level level) {
        if (level.dimension().equals(Level.NETHER)) {
            return 118;
        }
        if (level.dimension().equals(Level.END)) {
            return 250;
        }
        return level.getMaxBuildHeight() - 20;
    }

    private static LandingCheck checkLanding(Level level, int x, int z) {
        if (level.dimension().equals(Level.NETHER)) {
            return LandingCheck.unsafe(Component.translatable("error.faautoroute.auto_land_nether"));
        }

        Integer height = heightAt(level, x, z);
        if (height == null) {
            return LandingCheck.unloaded(Component.translatable("error.faautoroute.unloaded_destination"));
        }
        if (height <= level.getMinBuildHeight() + 2) {
            return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_void"));
        }

        BlockPos surfacePos = new BlockPos(x, height, z);
        BlockPos groundPos = new BlockPos(x, height - 1, z);
        BlockState surface = level.getBlockState(surfacePos);
        BlockState ground = level.getBlockState(groundPos);
        if (!surface.getFluidState().isEmpty() || !ground.getFluidState().isEmpty()) {
            return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_fluid"));
        }
        if (ground.isAir()) {
            return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_air"));
        }
        if (ground.getBlock() instanceof LeavesBlock) {
            return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_leaves"));
        }

        for (int dy = 1; dy <= 4; dy++) {
            BlockState above = level.getBlockState(new BlockPos(x, height + dy, z));
            if (above.getBlock() instanceof LeavesBlock) {
                return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_leaves"));
            }
            if (!above.getFluidState().isEmpty()) {
                return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_fluid"));
            }
            if (above.canOcclude()) {
                return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_obstructed"));
            }
        }

        int min = height;
        int max = height;
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                Integer nearby = heightAt(level, x + dx, z + dz);
                if (nearby == null) {
                    return LandingCheck.unloaded(Component.translatable("error.faautoroute.unloaded_destination"));
                }
                min = Math.min(min, nearby);
                max = Math.max(max, nearby);
            }
        }
        if (max - min > 3) {
            return LandingCheck.unsafe(Component.translatable("error.faautoroute.unsafe_landing_slope", max - min));
        }

        return LandingCheck.safe(height);
    }

    private record PlannedWaypoint(int x, int z, int altitude, int speed) {
    }

    private record Leg(RoutePoint a, RoutePoint b, double length, double ux, double uz) {
    }

    private record TerrainScan(int maxHeight, int unknownSamples, int samples) {
    }

    private record LandingCheck(boolean safe, int height, Component message, boolean unloaded) {
        static LandingCheck safe(int height) {
            return new LandingCheck(true, height, Component.empty(), false);
        }

        static LandingCheck unsafe(Component message) {
            return new LandingCheck(false, 0, message, false);
        }

        static LandingCheck unloaded(Component message) {
            return new LandingCheck(false, 0, message, true);
        }
    }
}
