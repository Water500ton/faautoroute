package com.water500t.faautoroute;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;
import ru.octol1ttle.flightassistant.api.computer.ComputerBus;
import ru.octol1ttle.flightassistant.api.computer.ComputerRegistrationCallback;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.AutoFlightComputer;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.modes.VerticalSpeedVerticalMode;

/** Client entrypoint. */
public final class AutoRouteClient implements ClientModInitializer {
    public static final String MOD_ID = "faautoroute";

    private static volatile ComputerBus computerBus;
    private static volatile RouteResult lastResult;
    private static volatile PendingLanding pendingLanding;
    private static final java.util.List<RoutePoint> VIA_POINTS = new java.util.ArrayList<>();
    private static KeyMapping openPlannerKey;
    private static final double FINALIZE_RANGE = 512.0;
    private static final double MIN_FINALIZE_RANGE = 48.0;
    private static final LandingLaw LANDING_LAW = new LandingLaw(LandingLaw.Config.DEFAULT);
    private static final double TERRAIN_SAMPLE_STEP = 8.0;
    private static final double TERRAIN_SAMPLE_RANGE = 320.0;
    private static final java.util.Map<Long, AutoFlightComputer.VerticalMode> VERTICAL_MODE_CACHE =
            new java.util.HashMap<>();

    @Override
    public void onInitializeClient() {
        ComputerRegistrationCallback.EVENT.register((computers, registerFunction) -> computerBus = computers);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> AutoRouteCommands.register(dispatcher));

        openPlannerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.faautoroute.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "key.categories.faautoroute"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPlannerKey.consumeClick()) {
                client.setScreen(new RoutePlannerScreen(client.screen));
            }
            syncPlanModes();
            tickPendingLanding(client);
            FlightTelemetry.tick(client);
        });
    }

    public static ComputerBus bus() {
        return computerBus;
    }

    public static RouteResult lastResult() {
        return lastResult;
    }

    public static void setLastResult(RouteResult result) {
        lastResult = result;
        LANDING_LAW.reset();
        if (result == null) {
            pendingLanding = null;
        }
    }

    /** Keep the autopilot's selected modes aligned with the current flight-plan phase. */
    public static void syncPlanModes() {
        ComputerBus bus = computerBus;
        if (bus == null) {
            return;
        }
        try {
            AutoFlightComputer autoflight = bus.getAutoflight();
            if (!autoflight.getAutopilot()) {
                LANDING_LAW.reset();
                return;
            }
            FlightPlanComputer plan = bus.getPlan();
            AutoFlightComputer.ThrustMode thrust = plan.getThrustMode();
            AutoFlightComputer.VerticalMode vertical = plan.getVerticalMode();
            AutoFlightComputer.LateralMode lateral = plan.getLateralMode();
            if (thrust != null) {
                autoflight.setSelectedThrustMode(thrust);
            }

            LandingLaw.State landingState = sampleLandingState(plan);
            LandingLaw.Decision decision = landingState == null
                    ? new LandingLaw.Decision(LandingLaw.Action.NONE, 0.0, false)
                    : LANDING_LAW.evaluate(landingState);

            if (decision.releaseAutopilot()) {
                autoflight.setSelectedVerticalMode(modeFor(LandingLaw.Action.ROLLOUT, 0.0));
                autoflight.setAutoThrust(false, false);
                autoflight.setAutoPilot(false, false);
                LANDING_LAW.reset();
                return;
            }

            switch (decision.action()) {
                case BRAKE, FLARE, ROLLOUT -> autoflight.setSelectedVerticalMode(
                        modeFor(decision.action(), decision.targetVerticalSpeed()));
                default -> {
                    if (vertical != null) {
                        autoflight.setSelectedVerticalMode(vertical);
                    }
                }
            }
            if (lateral != null) {
                autoflight.setSelectedLateralMode(lateral);
            }
        } catch (Throwable ignored) {
            // A missing/transitioning plan must never crash the client tick.
        }
    }

    /** Snapshot the aircraft state the landing law needs, or null when unavailable. */
    private static LandingLaw.State sampleLandingState(FlightPlanComputer plan) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || plan.getArrivalData().isDefault()) {
            return null;
        }
        FlightPlanComputer.ArrivalData arrival = plan.getArrivalData();
        int x = Mth.floor(client.player.getX());
        int z = Mth.floor(client.player.getZ());
        if (!client.level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        int ground = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        double horizontalSpeed = Math.hypot(client.player.getDeltaMovement().x,
                client.player.getDeltaMovement().z) * 20.0;
        double distance = Math.hypot(client.player.getX() - arrival.getCoordinatesX(),
                client.player.getZ() - arrival.getCoordinatesZ());
        return new LandingLaw.State(
                client.player.getY() - ground,
                client.player.getY() - arrival.getElevation(),
                client.player.getDeltaMovement().y * 20.0,
                horizontalSpeed,
                distance,
                terrainSinkLimit(client, arrival, horizontalSpeed, distance),
                client.player.onGround());
    }

    /**
     * Steepest sink rate that keeps the aircraft above ground standing between
     * the aircraft and the aim point, sampled every {@value #TERRAIN_SAMPLE_STEP}
     * blocks along the track. Infinite when nothing constrains the descent.
     */
    private static double terrainSinkLimit(Minecraft client, FlightPlanComputer.ArrivalData arrival,
                                           double horizontalSpeed, double distance) {
        if (distance < 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        double ux = (arrival.getCoordinatesX() - client.player.getX()) / distance;
        double uz = (arrival.getCoordinatesZ() - client.player.getZ()) / distance;
        double aimElevation = arrival.getElevation();
        LandingLaw.Config config = LandingLaw.Config.DEFAULT;
        double limit = Double.POSITIVE_INFINITY;
        for (double ahead = TERRAIN_SAMPLE_STEP;
                ahead < distance && ahead <= TERRAIN_SAMPLE_RANGE;
                ahead += ahead < 128.0 ? TERRAIN_SAMPLE_STEP : TERRAIN_SAMPLE_STEP * 4) {
            int sx = Mth.floor(client.player.getX() + ux * ahead);
            int sz = Mth.floor(client.player.getZ() + uz * ahead);
            if (!client.level.getChunkSource().hasChunk(sx >> 4, sz >> 4)) {
                continue;
            }
            int groundAhead = client.level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
            double constraint = LandingLaw.terrainConstraint(groundAhead, aimElevation, config);
            limit = Math.min(limit, (client.player.getY() - constraint) * horizontalSpeed / ahead);
        }
        return limit;
    }

    /** Cached vertical-speed mode so the tick loop does not allocate one per frame. */
    private static AutoFlightComputer.VerticalMode modeFor(LandingLaw.Action action, double target) {
        long key = (long) action.ordinal() << 32 | Math.round(target * 100.0) & 0xFFFFFFFFL;
        return VERTICAL_MODE_CACHE.computeIfAbsent(key, ignored -> new VerticalSpeedVerticalMode(
                target, Component.translatable(labelFor(action))));
    }

    private static String labelFor(LandingLaw.Action action) {
        return switch (action) {
            case BRAKE -> "mode.faautoroute.landing_brake";
            case FLARE -> "mode.faautoroute.flare";
            case TERRAIN -> "mode.faautoroute.terrain";
            default -> "mode.faautoroute.rollout";
        };
    }

    /** Engage AP and AT after forcing the selected modes onto the loaded flight plan. */
    public static boolean enableAutopilot() {
        ComputerBus bus = computerBus;
        if (bus == null) {
            return false;
        }
        syncPlanModes();
        bus.getAutoflight().setAutoPilot(true, false);
        bus.getAutoflight().setAutoThrust(true, false);
        syncPlanModes();
        return true;
    }

    /** Remember a deferred auto-land plan until its destination chunk is available. */
    public static void updatePendingLanding(RouteOptions options, RouteResult result) {
        if (result != null && result.deferredLanding()) {
            pendingLanding = new PendingLanding(options.destX(), options.destZ(),
                    result.cruiseAltitude(), options.speed());
        } else {
            pendingLanding = null;
        }
    }

    public static void clearPendingLanding() {
        pendingLanding = null;
    }

    public static boolean hasPendingLanding() {
        return pendingLanding != null;
    }

    private static void tickPendingLanding(Minecraft client) {
        PendingLanding pending = pendingLanding;
        if (pending == null || client.player == null || client.level == null) {
            return;
        }

        double distance = Math.hypot(pending.destX() - client.player.getX(), pending.destZ() - client.player.getZ());
        if (distance > FINALIZE_RANGE) {
            return;
        }
        if (!RoutePlanner.isChunkLoaded(client.level, pending.destX(), pending.destZ())) {
            return;
        }
        if (distance < MIN_FINALIZE_RANGE) {
            pendingLanding = null;
            client.player.displayClientMessage(Component.translatable("command.faautoroute.landing_too_close"), true);
            return;
        }

        ComputerBus bus = computerBus;
        if (bus == null) {
            return;
        }

        try {
            RouteOptions options = new RouteOptions(pending.destX(), pending.destZ(),
                    pending.cruise(), pending.speed(), true, client.player.onGround(), java.util.List.of());
            RouteResult result = RoutePlanner.plan(client.level, client.player.position(), options);
            if (result.deferredLanding()) {
                return;
            }

            bus.getPlan().reset();
            bus.getPlan().load(result.plan());
            lastResult = result;
            pendingLanding = null;
            syncPlanModes();
            XaeroIntegration.publish(result);
            client.player.displayClientMessage(Component.translatable("command.faautoroute.landing_finalized"), true);
        } catch (RoutePlanningException exception) {
            pendingLanding = null;
            client.player.displayClientMessage(exception.component(), true);
        } catch (RuntimeException exception) {
            pendingLanding = null;
            client.player.displayClientMessage(Component.translatable("command.faautoroute.error",
                    exception.getMessage() == null ? "?" : exception.getMessage()), true);
        }
    }

    /** Mutable list of user-selected via points, shared by the planner screens. */
    public static java.util.List<RoutePoint> viaPoints() {
        return VIA_POINTS;
    }

    public static void openPlanner(int x, int z) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new RoutePlannerScreen(client.screen, x, z));
    }

    public static void openPlanner() {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new RoutePlannerScreen(client.screen));
    }

    private record PendingLanding(int destX, int destZ, int cruise, int speed) {
    }
}
