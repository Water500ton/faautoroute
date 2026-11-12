package com.water500t.faautoroute;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import ru.octol1ttle.flightassistant.api.computer.ComputerBus;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer;

/**
 * The in-game AutoRoute planner screen. It is intentionally built from vanilla
 * widgets so it matches the rest of Minecraft and does not add another UI
 * dependency.
 */
public class RoutePlannerScreen extends Screen {
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 232;
    private static final int TEXT = 0xFFE5E7EB;
    private static final int MUTED = 0xFF94A3B8;
    private static final int ACCENT = 0xFF60A5FA;
    private static final int GREEN = 0xFF34D399;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFF87171;

    private final Screen parent;
    private final int initialX;
    private final int initialZ;

    private EditBox xField;
    private EditBox zField;
    private EditBox cruiseField;
    private EditBox speedField;
    private Button autoLandButton;
    private Button armApButton;

    private boolean autoLand;
    private boolean armAp;
    private boolean compact;
    private int left;
    private int top;
    private int panelWidth;
    private Component status = Component.translatable("gui.faautoroute.ready");
    private int statusColor = MUTED;
    private RouteResult result;

    public RoutePlannerScreen(Screen parent) {
        this(parent, defaultX(), defaultZ());
    }

    public RoutePlannerScreen(Screen parent, int initialX, int initialZ) {
        super(Component.translatable("gui.faautoroute.title"));
        this.parent = parent;
        this.initialX = initialX;
        this.initialZ = initialZ;
        this.result = AutoRouteClient.lastResult();
    }

    private static int defaultX() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 0 : minecraft.player.getBlockX();
    }

    private static int defaultZ() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 0 : minecraft.player.getBlockZ();
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(PANEL_WIDTH, this.width - 8);
        this.compact = this.panelWidth < 360;
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - PANEL_HEIGHT) / 2;

        int fieldWidth = Math.min(80, (this.panelWidth / 2) - 76);
        fieldWidth = Math.max(48, fieldWidth);
        int xFieldX = this.left + 68;
        int zFieldX = this.left + (this.panelWidth / 2) + 56;
        int row1 = this.top + 30;
        int row2 = this.top + 52;

        String previousX = this.xField != null ? this.xField.getValue() : Integer.toString(this.initialX);
        String previousZ = this.zField != null ? this.zField.getValue() : Integer.toString(this.initialZ);
        String previousCruise = this.cruiseField != null
                ? this.cruiseField.getValue()
                : (this.result != null ? Integer.toString(this.result.cruiseAltitude()) : "");
        String previousSpeed = this.speedField != null
                ? this.speedField.getValue()
                : Integer.toString(RoutePlanner.DEFAULT_SPEED);

        this.xField = new EditBox(this.font, xFieldX, row1, fieldWidth, 18, Component.translatable("gui.faautoroute.destination_x"));
        this.xField.setMaxLength(9);
        this.xField.setFilter(RoutePlannerScreen::isCoordinateText);
        this.xField.setValue(previousX);
        this.addRenderableWidget(this.xField);

        this.zField = new EditBox(this.font, zFieldX, row1, fieldWidth, 18, Component.translatable("gui.faautoroute.destination_z"));
        this.zField.setMaxLength(9);
        this.zField.setFilter(RoutePlannerScreen::isCoordinateText);
        this.zField.setValue(previousZ);
        this.addRenderableWidget(this.zField);

        this.cruiseField = new EditBox(this.font, xFieldX, row2, fieldWidth, 18, Component.translatable("gui.faautoroute.cruise"));
        this.cruiseField.setMaxLength(4);
        this.cruiseField.setFilter(RoutePlannerScreen::isCoordinateText);
        this.cruiseField.setHint(Component.translatable("gui.faautoroute.cruise_hint"));
        this.cruiseField.setValue(previousCruise);
        this.addRenderableWidget(this.cruiseField);

        this.speedField = new EditBox(this.font, zFieldX, row2, fieldWidth, 18, Component.translatable("gui.faautoroute.speed"));
        this.speedField.setMaxLength(2);
        this.speedField.setFilter(RoutePlannerScreen::isCoordinateText);
        this.speedField.setValue(previousSpeed);
        this.addRenderableWidget(this.speedField);

        int row3 = this.top + 76;
        int toolGap = 5;
        int toolWidth = (this.panelWidth - 32 - toolGap * 4) / 5;
        Component playerLabel = Component.translatable(this.compact ? "gui.faautoroute.use_player.short" : "gui.faautoroute.use_player");
        Component lookLabel = Component.translatable(this.compact ? "gui.faautoroute.use_look.short" : "gui.faautoroute.use_look");
        this.addRenderableWidget(Button.builder(playerLabel, button -> usePlayerPosition())
                .bounds(this.left + 16, row3, toolWidth, 20).build());
        this.addRenderableWidget(Button.builder(lookLabel, button -> useLookTarget())
                .bounds(this.left + 16 + (toolWidth + toolGap), row3, toolWidth, 20).build());
        Button xaero = Button.builder(Component.translatable("gui.faautoroute.xaero"), button -> openXaeroPicker())
                .bounds(this.left + 16 + (toolWidth + toolGap) * 2, row3, toolWidth, 20).build();
        xaero.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.faautoroute.xaero.tooltip")));
        this.addRenderableWidget(xaero);

        this.autoLandButton = Button.builder(autoLandLabel(), button -> {
            this.autoLand = !this.autoLand;
            button.setMessage(autoLandLabel());
        }).bounds(this.left + 16 + (toolWidth + toolGap) * 3, row3, toolWidth, 20).build();
        this.autoLandButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.faautoroute.auto_land.tooltip")));
        this.addRenderableWidget(this.autoLandButton);

        this.armApButton = Button.builder(armApLabel(), button -> {
            this.armAp = !this.armAp;
            button.setMessage(armApLabel());
        }).bounds(this.left + 16 + (toolWidth + toolGap) * 4, row3, toolWidth, 20).build();
        this.armApButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.faautoroute.arm_ap.tooltip")));
        this.addRenderableWidget(this.armApButton);

        int buttonY = this.top + 206;
        int buttonGap = 6;
        int buttonWidth = (this.panelWidth - 32 - buttonGap * 4) / 5;
        Component planLabel = Component.translatable(this.compact ? "gui.faautoroute.plan.short" : "gui.faautoroute.plan");
        Component waypointsLabel = Component.translatable(this.compact ? "gui.faautoroute.waypoints.short" : "gui.faautoroute.waypoints");
        this.addRenderableWidget(Button.builder(planLabel, button -> plan(true))
                .bounds(this.left + 16, buttonY, buttonWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.preview"), button -> plan(false))
                .bounds(this.left + 16 + (buttonWidth + buttonGap), buttonY, buttonWidth, 18).build());
        this.addRenderableWidget(Button.builder(waypointsLabel, button -> openWaypoints())
                .bounds(this.left + 16 + (buttonWidth + buttonGap) * 2, buttonY, buttonWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.clear"), button -> clearPlan())
                .bounds(this.left + 16 + (buttonWidth + buttonGap) * 3, buttonY, buttonWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.done"), button -> onClose())
                .bounds(this.left + 16 + (buttonWidth + buttonGap) * 4, buttonY, buttonWidth, 18).build());
    }

    private static boolean isCoordinateText(String value) {
        return value.isEmpty() || value.equals("-") || value.matches("-?\\d{1,9}");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int right = this.left + this.panelWidth;
        int bottom = this.top + PANEL_HEIGHT;
        graphics.fill(this.left - 1, this.top - 1, right + 1, bottom + 1, 0xFF1E3A5F);
        graphics.fill(this.left, this.top, right, bottom, 0xEE0B1220);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.left + this.panelWidth / 2, this.top + 8, ACCENT);
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.destination_x"), this.left + 16, this.top + 35, MUTED, false);
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.destination_z"), this.left + (this.panelWidth / 2) + 8, this.top + 35, MUTED, false);
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.cruise"), this.left + 16, this.top + 57, MUTED, false);
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.speed"), this.left + (this.panelWidth / 2) + 8, this.top + 57, MUTED, false);

        int contentWidth = this.panelWidth - 32;
        int mapWidth = Math.min(180, (int) (contentWidth * 0.48));
        int mapX = this.left + 16;
        int mapY = this.top + 102;
        int mapHeight = 80;
        drawMap(graphics, mapX, mapY, mapWidth, mapHeight);
        drawSummary(graphics, mapX + mapWidth + 12, mapY, contentWidth - mapWidth - 12, mapHeight);

        List<FormattedCharSequence> lines = this.font.split(this.status, this.panelWidth - 32);
        int statusY = this.top + 186;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            graphics.drawString(this.font, lines.get(i), this.left + 16, statusY + i * 10, this.statusColor, false);
        }
    }

    private void drawMap(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xCC0A0F17);
        graphics.fill(x, y, x + width, y + 1, 0xFF334155);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF334155);
        graphics.fill(x, y, x + 1, y + height, 0xFF334155);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF334155);

        if (this.result == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.faautoroute.preview_hint"), x + width / 2, y + height / 2 - 4, MUTED);
            return;
        }

        List<FlightPlanComputer.EnrouteWaypoint> waypoints = this.result.waypoints();
        FlightPlanComputer.DepartureData departure = this.result.plan().getDeparture();
        FlightPlanComputer.ArrivalData arrival = this.result.plan().getArrival();

        int minX = departure.getCoordinatesX();
        int maxX = minX;
        int minZ = departure.getCoordinatesZ();
        int maxZ = minZ;
        for (FlightPlanComputer.EnrouteWaypoint waypoint : waypoints) {
            minX = Math.min(minX, waypoint.getCoordinatesX());
            maxX = Math.max(maxX, waypoint.getCoordinatesX());
            minZ = Math.min(minZ, waypoint.getCoordinatesZ());
            maxZ = Math.max(maxZ, waypoint.getCoordinatesZ());
        }
        if (!arrival.isDefault()) {
            minX = Math.min(minX, arrival.getCoordinatesX());
            maxX = Math.max(maxX, arrival.getCoordinatesX());
            minZ = Math.min(minZ, arrival.getCoordinatesZ());
            maxZ = Math.max(maxZ, arrival.getCoordinatesZ());
        }

        int pad = 8;
        double spanX = Math.max(1, maxX - minX);
        double spanZ = Math.max(1, maxZ - minZ);
        double scale = Math.min((width - pad * 2) / spanX, (height - pad * 2) / spanZ);
        int drawW = (int) Math.round(spanX * scale);
        int drawH = (int) Math.round(spanZ * scale);
        int offsetX = x + (width - drawW) / 2;
        int offsetY = y + (height - drawH) / 2;

        for (int i = 1; i <= 3; i++) {
            int gx = offsetX + drawW * i / 4;
            int gy = offsetY + drawH * i / 4;
            graphics.fill(gx, y + 2, gx + 1, y + height - 2, 0x22334A66);
            graphics.fill(x + 2, gy, x + width - 2, gy + 1, 0x22334A66);
        }

        int lastX = projectX(departure.getCoordinatesX(), minX, scale, offsetX);
        int lastY = projectY(departure.getCoordinatesZ(), maxZ, scale, offsetY);
        for (FlightPlanComputer.EnrouteWaypoint waypoint : waypoints) {
            int px = projectX(waypoint.getCoordinatesX(), minX, scale, offsetX);
            int py = projectY(waypoint.getCoordinatesZ(), maxZ, scale, offsetY);
            drawLine(graphics, lastX, lastY, px, py, ACCENT);
            lastX = px;
            lastY = py;
        }
        if (!arrival.isDefault()) {
            int px = projectX(arrival.getCoordinatesX(), minX, scale, offsetX);
            int py = projectY(arrival.getCoordinatesZ(), maxZ, scale, offsetY);
            drawLine(graphics, lastX, lastY, px, py, AMBER);
        }

        for (FlightPlanComputer.EnrouteWaypoint waypoint : waypoints) {
            int px = projectX(waypoint.getCoordinatesX(), minX, scale, offsetX);
            int py = projectY(waypoint.getCoordinatesZ(), maxZ, scale, offsetY);
            graphics.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFDE68A);
        }

        int startX = projectX(departure.getCoordinatesX(), minX, scale, offsetX);
        int startY = projectY(departure.getCoordinatesZ(), maxZ, scale, offsetY);
        graphics.fill(startX - 3, startY - 3, startX + 4, startY + 4, GREEN);

        if (!arrival.isDefault()) {
            int destX = projectX(arrival.getCoordinatesX(), minX, scale, offsetX);
            int destY = projectY(arrival.getCoordinatesZ(), maxZ, scale, offsetY);
            graphics.fill(destX - 3, destY - 3, destX + 4, destY + 4, RED);
        }

        graphics.drawString(this.font, "N", x + width - 10, y + 3, MUTED, false);
    }

    private void drawSummary(GuiGraphics graphics, int x, int y, int width, int height) {
        if (this.result == null) {
            graphics.drawString(this.font, Component.translatable("gui.faautoroute.preview_hint"), x, y + 4, MUTED, false);
            return;
        }

        int lineY = y + 2;
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.summary_distance", this.result.distance()), x, lineY, TEXT, false);
        lineY += 11;
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.summary_cruise", this.result.cruiseAltitude()), x, lineY, TEXT, false);
        lineY += 11;
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.summary_waypoints", this.result.waypointCount()), x, lineY, TEXT, false);
        lineY += 11;
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.summary_via", AutoRouteClient.viaPoints().size()), x, lineY, TEXT, false);
        lineY += 11;
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.summary_eta", formatTime(this.result.estimatedSeconds())), x, lineY, TEXT, false);
        lineY += 11;
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.summary_land",
                Component.translatable(this.result.autoLand() ? "gui.faautoroute.on" : "gui.faautoroute.off")), x, lineY, TEXT, false);
        lineY += 12;

        int warningLines = 0;
        for (Component warning : this.result.warnings()) {
            List<FormattedCharSequence> wrapped = this.font.split(warning, width);
            for (FormattedCharSequence line : wrapped) {
                if (warningLines >= 1 || lineY > y + height - 10) {
                    return;
                }
                graphics.drawString(this.font, line, x, lineY, AMBER, false);
                lineY += 10;
                warningLines++;
            }
        }
    }

    private static int projectX(int worldX, int minX, double scale, int offsetX) {
        return (int) Math.round(offsetX + (worldX - minX) * scale);
    }

    private static int projectY(int worldZ, int maxZ, double scale, int offsetY) {
        return (int) Math.round(offsetY + (maxZ - worldZ) * scale);
    }

    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = -Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx + dy;
        int x = x1;
        int y = y1;
        while (true) {
            graphics.fill(x, y, x + 1, y + 1, color);
            if (x == x2 && y == y2) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static String formatTime(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void usePlayerPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            setStatus(Component.translatable("gui.faautoroute.no_world"), RED);
            return;
        }
        this.xField.setValue(Integer.toString(minecraft.player.getBlockX()));
        this.zField.setValue(Integer.toString(minecraft.player.getBlockZ()));
        setStatus(Component.translatable("gui.faautoroute.player_set"), MUTED);
    }

    private void useLookTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            setStatus(Component.translatable("gui.faautoroute.no_world"), RED);
            return;
        }

        HitResult hit = minecraft.player.pick(384.0D, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            this.xField.setValue(Integer.toString(blockHit.getBlockPos().getX()));
            this.zField.setValue(Integer.toString(blockHit.getBlockPos().getZ()));
            setStatus(Component.translatable("gui.faautoroute.look_set"), MUTED);
            return;
        }

        double x = minecraft.player.getX() + minecraft.player.getLookAngle().x * 256.0D;
        double z = minecraft.player.getZ() + minecraft.player.getLookAngle().z * 256.0D;
        this.xField.setValue(Integer.toString(Mth.floor(x)));
        this.zField.setValue(Integer.toString(Mth.floor(z)));
        setStatus(Component.translatable("gui.faautoroute.look_miss"), AMBER);
    }

    private void plan(boolean load) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            setStatus(Component.translatable("gui.faautoroute.no_world"), RED);
            return;
        }
        ComputerBus bus = AutoRouteClient.bus();
        if (bus == null) {
            setStatus(Component.translatable("gui.faautoroute.no_fa"), RED);
            return;
        }

        Integer destX = parse(xField, "gui.faautoroute.destination_x", -30_000_000, 30_000_000);
        if (destX == null) {
            return;
        }
        Integer destZ = parse(zField, "gui.faautoroute.destination_z", -30_000_000, 30_000_000);
        if (destZ == null) {
            return;
        }
        Integer cruise = null;
        if (!this.cruiseField.getValue().isBlank()) {
            cruise = parse(cruiseField, "gui.faautoroute.cruise", -120, 1200);
            if (cruise == null) {
                return;
            }
        }
        Integer speed = parse(speedField, "gui.faautoroute.speed", 0, 30);
        if (speed == null) {
            return;
        }

        try {
            RouteOptions options = new RouteOptions(destX, destZ, cruise, speed, this.autoLand,
                    minecraft.player.onGround(), List.copyOf(AutoRouteClient.viaPoints()));
            RouteResult planned = RoutePlanner.plan(minecraft.level, minecraft.player.position(), options);
            this.result = planned;
            AutoRouteClient.setLastResult(planned);
            XaeroIntegration.publish(planned);

            if (load) {
                bus.getPlan().reset();
                bus.getPlan().load(planned.plan());
                AutoRouteClient.updatePendingLanding(options, planned);
                if (this.armAp) {
                    AutoRouteClient.enableAutopilot();
                } else {
                    AutoRouteClient.syncPlanModes();
                }
                setStatus(Component.translatable("gui.faautoroute.loaded", planned.waypointCount(), planned.cruiseAltitude(), planned.distance()), GREEN);
            } else {
                setStatus(Component.translatable("gui.faautoroute.preview_ready", planned.waypointCount(), planned.cruiseAltitude()), ACCENT);
            }
        } catch (RoutePlanningException exception) {
            setStatus(exception.component(), RED);
        } catch (RuntimeException exception) {
            setStatus(Component.translatable("gui.faautoroute.error", exception.getMessage() == null ? "?" : exception.getMessage()), RED);
        }
    }

    private Integer parse(EditBox field, String labelKey, int min, int max) {
        String value = field.getValue().trim();
        if (value.isEmpty()) {
            setStatus(Component.translatable("gui.faautoroute.bad_number", Component.translatable(labelKey)), RED);
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                setStatus(Component.translatable("gui.faautoroute.out_of_range", Component.translatable(labelKey), min, max), RED);
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            setStatus(Component.translatable("gui.faautoroute.bad_number", Component.translatable(labelKey)), RED);
            return null;
        }
    }

    private Component autoLandLabel() {
        Component state = Component.translatable(this.autoLand ? "gui.faautoroute.on" : "gui.faautoroute.off");
        return Component.translatable(this.compact ? "gui.faautoroute.auto_land.state.compact" : "gui.faautoroute.auto_land.state", state);
    }

    private Component armApLabel() {
        Component state = Component.translatable(this.armAp ? "gui.faautoroute.on" : "gui.faautoroute.off");
        return Component.translatable(this.compact ? "gui.faautoroute.arm_ap.state.compact" : "gui.faautoroute.arm_ap.state", state);
    }

    private void openXaeroPicker() {
        if (!XaeroIntegration.isAvailable()) {
            setStatus(Component.translatable("gui.faautoroute.xaero_missing"), AMBER);
            return;
        }
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new XaeroWaypointScreen(this, waypoint -> {
            this.xField.setValue(Integer.toString(waypoint.x()));
            this.zField.setValue(Integer.toString(waypoint.z()));
            setStatus(Component.translatable("gui.faautoroute.xaero_selected", waypoint.name()), ACCENT);
        }));
    }

    private void openWaypoints() {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new WaypointListScreen(this));
    }

    private void clearPlan() {
        ComputerBus bus = AutoRouteClient.bus();
        if (bus == null) {
            setStatus(Component.translatable("gui.faautoroute.no_fa"), RED);
            return;
        }
        FlightPlanComputer.FlightPlan empty = new FlightPlanComputer.FlightPlan(
                new FlightPlanComputer.DepartureData(0, 0, 0, 0.0f),
                new ArrayList<>(),
                new FlightPlanComputer.ArrivalData(0, 0, 0, 0.0f, 0,
                        FlightPlanComputer.ArrivalData.MinimumsType.ABSOLUTE, 0, 0)
        );
        bus.getPlan().reset();
        bus.getPlan().load(empty);
        bus.getAutoflight().setAutoPilot(false, false);
        bus.getAutoflight().setAutoThrust(false, false);
        this.result = null;
        AutoRouteClient.viaPoints().clear();
        AutoRouteClient.setLastResult(null);
        AutoRouteClient.clearPendingLanding();
        XaeroIntegration.clear();
        setStatus(Component.translatable("gui.faautoroute.cleared"), GREEN);
    }

    private void setStatus(Component component, int color) {
        this.status = component;
        this.statusColor = color;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            plan(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
