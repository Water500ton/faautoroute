package com.water500t.faautoroute;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer;

/**
 * Optional, reflection-based integration with Xaero's Minimap / World Map.
 *
 * <p>We use the public third-party waypoint API instead of compiling against
 * Xaero, so the mod still works when Xaero is not installed and does not force
 * a particular Xaero build at class-load time.</p>
 */
public final class XaeroIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoRoute/Xaero");
    private static final ResourceLocation ORIGIN = ResourceLocation.fromNamespaceAndPath("faautoroute", "route");
    private static Boolean available;

    private XaeroIntegration() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = FabricLoader.getInstance().isModLoaded("xaerominimap") && classPresent("xaero.common.XaeroMinimapSession");
        }
        return available;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, XaeroIntegration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

    /** Replace the temporary route displayed on the Xaero map. */
    public static void publish(RouteResult result) {
        if (!isAvailable()) {
            return;
        }
        try {
            Object group = thirdPartyGroup();
            if (group == null) {
                return;
            }
            group.getClass().getMethod("clear").invoke(group);
            try {
                group.getClass().getMethod("setEnabledStateGetter", Supplier.class)
                        .invoke(group, (Supplier<Boolean>) () -> true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Older Xaero builds do not expose a compatible enable-state hook.
            }

            FlightPlanComputer.DepartureData departure = result.plan().getDeparture();
            add(group, "start", "AR START", "S",
                    departure.getCoordinatesX(), departure.getElevation(), departure.getCoordinatesZ(),
                    "GREEN", "NORMAL");

            int index = 0;
            for (FlightPlanComputer.EnrouteWaypoint waypoint : result.waypoints()) {
                index++;
                add(group, "wp" + index, "AR " + index, "A",
                        waypoint.getCoordinatesX(), waypoint.getAltitude(), waypoint.getCoordinatesZ(),
                        "AQUA", "NORMAL");
            }

            FlightPlanComputer.ArrivalData arrival = result.plan().getArrival();
            if (!arrival.isDefault()) {
                add(group, "dest", "AR DEST", "D",
                        arrival.getCoordinatesX(), arrival.getElevation(), arrival.getCoordinatesZ(),
                        "RED", "NORMAL");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Could not publish route to Xaero", exception);
        }
    }

    public static void clear() {
        if (!isAvailable()) {
            return;
        }
        try {
            Object group = thirdPartyGroup();
            if (group != null) {
                group.getClass().getMethod("clear").invoke(group);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Could not clear Xaero route", exception);
        }
    }

    /** Waypoints from the current Xaero world, closest first. */
    public static List<XaeroWaypoint> readWaypoints() {
        List<XaeroWaypoint> result = new ArrayList<>();
        if (!isAvailable()) {
            return result;
        }
        try {
            Object session = currentSession();
            if (session == null) {
                return result;
            }
            Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
            Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
            if (world == null) {
                return result;
            }
            Object set = world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
            if (set == null) {
                return result;
            }
            Object iterable = set.getClass().getMethod("getWaypoints").invoke(set);
            for (Object waypoint : (Iterable<?>) iterable) {
                boolean disabled = (boolean) waypoint.getClass().getMethod("isDisabled").invoke(waypoint);
                if (disabled) {
                    continue;
                }
                String name = (String) waypoint.getClass().getMethod("getName").invoke(waypoint);
                int x = (int) waypoint.getClass().getMethod("getX").invoke(waypoint);
                int y = (int) waypoint.getClass().getMethod("getY").invoke(waypoint);
                int z = (int) waypoint.getClass().getMethod("getZ").invoke(waypoint);
                result.add(new XaeroWaypoint(name, x, y, z));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Could not read Xaero waypoints", exception);
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            double px = minecraft.player.getX();
            double pz = minecraft.player.getZ();
            result.sort(Comparator.comparingDouble(waypoint -> {
                double dx = waypoint.x() - px;
                double dz = waypoint.z() - pz;
                return dx * dx + dz * dz;
            }));
        }
        return result;
    }

    private static Object thirdPartyGroup() throws ReflectiveOperationException {
        Object session = currentSession();
        if (session == null) {
            return null;
        }
        Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
        Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
        if (world == null) {
            return null;
        }
        Object container = world.getClass().getMethod("getContainer").invoke(world);
        Object thirdParty = container.getClass().getMethod("getThirdPartyWaypointManager").invoke(container);
        Method get = thirdParty.getClass().getMethod("get", ResourceLocation.class);
        return get.invoke(thirdParty, ORIGIN);
    }

    private static Object currentSession() throws ReflectiveOperationException {
        Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
        return sessionClass.getMethod("getCurrentSession").invoke(null);
    }

    private static void add(
            Object group,
            String id,
            String name,
            String symbol,
            int x,
            int y,
            int z,
            String color,
            String purpose
    ) throws ReflectiveOperationException {
        Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
        Class<?> colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
        Class<?> purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object colorValue = Enum.valueOf((Class) colorClass, color);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object purposeValue = Enum.valueOf((Class) purposeClass, purpose);
        Constructor<?> constructor = waypointClass.getConstructor(
                int.class, int.class, int.class,
                String.class, String.class,
                colorClass, purposeClass, boolean.class
        );
        Object waypoint = constructor.newInstance(x, y, z, name, symbol, colorValue, purposeValue, true);
        group.getClass().getMethod("add", String.class, waypointClass).invoke(group, id, waypoint);
    }

    public record XaeroWaypoint(String name, int x, int y, int z) {
    }
}
