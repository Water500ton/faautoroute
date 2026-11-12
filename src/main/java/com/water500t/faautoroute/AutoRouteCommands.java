package com.water500t.faautoroute;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import ru.octol1ttle.flightassistant.api.computer.ComputerBus;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side command entrypoints, useful for macros and for opening the GUI. */
public final class AutoRouteCommands {
    private AutoRouteCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = literal("autoroute")
                .executes(context -> openGui(context, null))
                .then(literal("gui").executes(context -> openGui(context, null)))
                .then(literal("here").executes(AutoRouteCommands::openHere))
                .then(literal("addhere").executes(AutoRouteCommands::addHere))
                .then(literal("clearvia").executes(AutoRouteCommands::clearVia))
                .then(literal("waypoints").executes(AutoRouteCommands::openWaypoints))
                .then(literal("ap").executes(AutoRouteCommands::engageAutopilot))
                .then(literal("clear").executes(AutoRouteCommands::clear))
                .then(literal("info").executes(AutoRouteCommands::info));

        var land = literal("land");
        attachPlanArguments(land, true);
        root.then(land);

        var plan = literal("plan");
        attachPlanArguments(plan, false);
        root.then(plan);

        attachPlanArguments(root, false);
        dispatcher.register(root);
    }

    private static void attachPlanArguments(ArgumentBuilder<FabricClientCommandSource, ?> parent, boolean autoLand) {
        parent.then(argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                .then(argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                        .executes(context -> plan(context, autoLand, null, RoutePlanner.DEFAULT_SPEED))
                        .then(argument("cruise", IntegerArgumentType.integer(-120, 1200))
                                .executes(context -> plan(context, autoLand,
                                        IntegerArgumentType.getInteger(context, "cruise"), RoutePlanner.DEFAULT_SPEED))
                                .then(argument("speed", IntegerArgumentType.integer(0, 30))
                                        .executes(context -> plan(context, autoLand,
                                                IntegerArgumentType.getInteger(context, "cruise"),
                                                IntegerArgumentType.getInteger(context, "speed")))))));
    }

    private static int openGui(CommandContext<FabricClientCommandSource> context, Integer ignored) {
        AutoRouteClient.openPlanner();
        return 1;
    }

    private static int addHere(CommandContext<FabricClientCommandSource> context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_world"));
            return 0;
        }
        AutoRouteClient.viaPoints().add(new RoutePoint(minecraft.player.getBlockX(), minecraft.player.getBlockZ()));
        context.getSource().sendFeedback(Component.translatable("command.faautoroute.addhere",
                AutoRouteClient.viaPoints().size(), minecraft.player.getBlockX(), minecraft.player.getBlockZ()));
        return 1;
    }

    private static int clearVia(CommandContext<FabricClientCommandSource> context) {
        AutoRouteClient.viaPoints().clear();
        context.getSource().sendFeedback(Component.translatable("command.faautoroute.clearvia"));
        return 1;
    }

    private static int openWaypoints(CommandContext<FabricClientCommandSource> context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_world"));
            return 0;
        }
        minecraft.setScreen(new WaypointListScreen(null));
        return 1;
    }

    private static int openHere(CommandContext<FabricClientCommandSource> context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_world"));
            return 0;
        }

        HitResult hit = minecraft.player.pick(384.0D, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            AutoRouteClient.openPlanner(blockHit.getBlockPos().getX(), blockHit.getBlockPos().getZ());
        } else {
            AutoRouteClient.openPlanner(
                    (int) Math.floor(minecraft.player.getX() + minecraft.player.getLookAngle().x * 256.0D),
                    (int) Math.floor(minecraft.player.getZ() + minecraft.player.getLookAngle().z * 256.0D)
            );
        }
        return 1;
    }

    private static int plan(CommandContext<FabricClientCommandSource> context, boolean autoLand, Integer cruise, int speed) {
        Minecraft minecraft = Minecraft.getInstance();
        FabricClientCommandSource source = context.getSource();
        if (minecraft.player == null || minecraft.level == null) {
            source.sendError(Component.translatable("command.faautoroute.no_world"));
            return 0;
        }
        ComputerBus bus = AutoRouteClient.bus();
        if (bus == null) {
            source.sendError(Component.translatable("command.faautoroute.no_fa"));
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "x");
        int z = IntegerArgumentType.getInteger(context, "z");
        try {
            RouteOptions options = new RouteOptions(x, z, cruise, speed, autoLand,
                    minecraft.player.onGround(), java.util.List.copyOf(AutoRouteClient.viaPoints()));
            RouteResult result = RoutePlanner.plan(minecraft.level, minecraft.player.position(), options);
            bus.getPlan().reset();
            bus.getPlan().load(result.plan());
            AutoRouteClient.setLastResult(result);
            AutoRouteClient.updatePendingLanding(options, result);
            AutoRouteClient.syncPlanModes();
            XaeroIntegration.publish(result);
            source.sendFeedback(Component.translatable("command.faautoroute.loaded",
                    result.waypointCount(), result.cruiseAltitude(), result.distance()));
            for (Component warning : result.warnings()) {
                source.sendFeedback(warning);
            }
            return 1;
        } catch (RoutePlanningException exception) {
            source.sendError(exception.component());
            return 0;
        } catch (RuntimeException exception) {
            source.sendError(Component.translatable("command.faautoroute.error",
                    exception.getMessage() == null ? "?" : exception.getMessage()));
            return 0;
        }
    }

    private static int engageAutopilot(CommandContext<FabricClientCommandSource> context) {
        ComputerBus bus = AutoRouteClient.bus();
        if (bus == null) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_fa"));
            return 0;
        }
        if (bus.getPlan().getEnrouteData().isEmpty()) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_plan"));
            return 0;
        }
        if (!AutoRouteClient.enableAutopilot()) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_fa"));
            return 0;
        }
        context.getSource().sendFeedback(Component.translatable("command.faautoroute.ap_on"));
        return 1;
    }

    private static int clear(CommandContext<FabricClientCommandSource> context) {
        ComputerBus bus = AutoRouteClient.bus();
        if (bus == null) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_fa"));
            return 0;
        }
        bus.getPlan().reset();
        bus.getPlan().load(new FlightPlanComputer.FlightPlan(
                new FlightPlanComputer.DepartureData(0, 0, 0, 0.0f),
                new java.util.ArrayList<>(),
                new FlightPlanComputer.ArrivalData(0, 0, 0, 0.0f, 0,
                        FlightPlanComputer.ArrivalData.MinimumsType.ABSOLUTE, 0, 0)
        ));
        bus.getAutoflight().setAutoPilot(false, false);
        bus.getAutoflight().setAutoThrust(false, false);
        AutoRouteClient.viaPoints().clear();
        AutoRouteClient.setLastResult(null);
        AutoRouteClient.clearPendingLanding();
        XaeroIntegration.clear();
        context.getSource().sendFeedback(Component.translatable("command.faautoroute.cleared"));
        return 1;
    }

    private static int info(CommandContext<FabricClientCommandSource> context) {
        ComputerBus bus = AutoRouteClient.bus();
        if (bus == null) {
            context.getSource().sendError(Component.translatable("command.faautoroute.no_fa"));
            return 0;
        }
        FlightPlanComputer plan = bus.getPlan();
        Component arrival = plan.getArrivalData().isDefault()
                ? Component.translatable("command.faautoroute.info_overflight")
                : Component.translatable("command.faautoroute.info_arrival",
                        plan.getArrivalData().getCoordinatesX(), plan.getArrivalData().getCoordinatesZ());
        context.getSource().sendFeedback(Component.translatable("command.faautoroute.info",
                plan.getEnrouteData().size(), arrival));
        return 1;
    }
}
