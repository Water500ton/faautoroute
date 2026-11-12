package com.water500t.faautoroute;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.AutoFlightComputer;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.modes.VerticalSpeedVerticalMode;

/**
 * Optional CSV flight telemetry, used to tune the landing brake/flare.
 *
 * <p>Enable with {@code -Dfaautoroute.telemetry=1}. The file is created in the
 * game directory and truncated once per session. Fully disabled (no allocation,
 * no file access) when the property is absent, which is the shipped default.</p>
 */
final class FlightTelemetry {
    private static final int INTERVAL_TICKS = 2;
    private static final boolean ENABLED = Boolean.getBoolean("faautoroute.telemetry");
    private static final String HEADER =
            "tick,x,y,z,agl,h_speed,v_speed,dist,hp,on_ground,ap,at,v_mode,v_target";

    private static BufferedWriter writer;
    private static boolean failed;
    private static int ticks;

    private FlightTelemetry() {
    }

    static void tick(Minecraft client) {
        if (!ENABLED || failed || client.player == null || client.level == null) {
            return;
        }
        if (++ticks % INTERVAL_TICKS != 0) {
            return;
        }
        try {
            if (writer == null) {
                Path path = client.gameDirectory.toPath().resolve("faautoroute-telemetry.csv");
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                writer.write(HEADER);
                writer.newLine();
            }
            writer.write(row(client));
            writer.newLine();
            if (ticks % 40 == 0) {
                writer.flush();
            }
        } catch (IOException exception) {
            failed = true;
        }
    }

    private static String row(Minecraft client) {
        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        double agl = -1.0;
        if (client.level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
            agl = y - client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        }
        double horizontalSpeed = Math.hypot(client.player.getDeltaMovement().x,
                client.player.getDeltaMovement().z) * 20.0;
        double verticalSpeed = client.player.getDeltaMovement().y * 20.0;

        double dist = -1.0;
        RouteResult result = AutoRouteClient.lastResult();
        if (result != null && result.autoLand()) {
            dist = Math.hypot(x - result.plan().getArrival().getCoordinatesX(),
                    z - result.plan().getArrival().getCoordinatesZ());
        }

        boolean autopilot = false;
        boolean autoThrust = false;
        String mode = "-";
        double modeTarget = 0.0;
        if (AutoRouteClient.bus() != null) {
            AutoFlightComputer autoflight = AutoRouteClient.bus().getAutoflight();
            autopilot = autoflight.getAutopilot();
            autoThrust = autoflight.getAutoThrust();
            AutoFlightComputer.VerticalMode vertical = autoflight.getActiveVerticalMode();
            if (vertical != null) {
                mode = vertical.getClass().getSimpleName();
                if (vertical instanceof VerticalSpeedVerticalMode speedMode) {
                    modeTarget = speedMode.getTargetVerticalSpeed();
                }
            }
        }

        return String.format(java.util.Locale.ROOT,
                "%d,%.2f,%.2f,%.2f,%.1f,%.2f,%.2f,%.1f,%.1f,%b,%b,%b,%s,%.2f",
                ticks, x, y, z, agl, horizontalSpeed, verticalSpeed, dist,
                client.player.getHealth(), client.player.onGround(), autopilot, autoThrust,
                mode, modeTarget);
    }
}
