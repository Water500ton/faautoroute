package com.water500t.faautoroute;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Manage the ordered list of user waypoints used by the planner. */
public class WaypointListScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 230;
    private static final int MAX_VISIBLE = 5;

    private final Screen parent;
    private final List<RoutePoint> points = AutoRouteClient.viaPoints();
    private Component note;
    private int left;
    private int top;
    private int panelWidth;

    public WaypointListScreen(Screen parent) {
        super(Component.translatable("gui.faautoroute.waypoints.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(PANEL_WIDTH, this.width - 8);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - PANEL_HEIGHT) / 2;

        int topGap = 4;
        int topWidth = (this.panelWidth - 32 - topGap * 2) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.waypoints.add_current"),
                button -> addCurrent()).bounds(this.left + 16, this.top + 32, topWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.waypoints.add_xaero"),
                button -> addXaero()).bounds(this.left + 16 + topWidth + topGap, this.top + 32, topWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.waypoints.remove_last"),
                button -> removeLast()).bounds(this.left + 16 + (topWidth + topGap) * 2, this.top + 32, topWidth, 20).build());

        int shown = Math.min(this.points.size(), MAX_VISIBLE);
        for (int i = 0; i < shown; i++) {
            RoutePoint point = this.points.get(i);
            String label = (i + 1) + ".  " + point.x() + ", " + point.z();
            this.addRenderableWidget(Button.builder(Component.literal(label), button -> {
                this.points.remove(point);
                reopen();
            }).bounds(this.left + 20, this.top + 62 + i * 20, this.panelWidth - 40, 18).build());
        }

        int bottom = this.top + PANEL_HEIGHT - 28;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.waypoints.clear"), button -> {
            this.points.clear();
            reopen();
        }).bounds(this.left + 20, bottom, 80, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.done"), button -> this.onClose())
                .bounds(this.left + this.panelWidth - 80, bottom, 60, 18).build());
    }

    private void addCurrent() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            this.note = Component.translatable("gui.faautoroute.no_world");
            return;
        }
        this.points.add(new RoutePoint(minecraft.player.getBlockX(), minecraft.player.getBlockZ()));
        reopen();
    }

    private void addXaero() {
        if (!XaeroIntegration.isAvailable()) {
            this.note = Component.translatable("gui.faautoroute.xaero_missing");
            return;
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(new XaeroWaypointScreen(this, waypoint ->
                    this.points.add(new RoutePoint(waypoint.x(), waypoint.z()))));
        }
    }

    private void removeLast() {
        if (!this.points.isEmpty()) {
            this.points.remove(this.points.size() - 1);
        }
        reopen();
    }

    private void reopen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new WaypointListScreen(this.parent));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int right = this.left + this.panelWidth;
        int bottom = this.top + PANEL_HEIGHT;
        graphics.fill(this.left - 1, this.top - 1, right + 1, bottom + 1, 0xFF1E3A5F);
        graphics.fill(this.left, this.top, right, bottom, 0xEE0B1220);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.left + this.panelWidth / 2, this.top + 8, 0xFF60A5FA);
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.waypoints.count", this.points.size()),
                this.left + 20, this.top + 20, 0xFF94A3B8, false);

        if (this.points.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.faautoroute.waypoints.empty"),
                    this.left + this.panelWidth / 2, this.top + 110, 0xFF94A3B8);
        } else if (this.points.size() > MAX_VISIBLE) {
            graphics.drawString(this.font, Component.translatable("gui.faautoroute.waypoints.more", this.points.size() - MAX_VISIBLE),
                    this.left + 20, this.top + 62 + MAX_VISIBLE * 20, 0xFFFBBF24, false);
        }
        if (this.note != null) {
            graphics.drawString(this.font, this.note, this.left + 20, this.top + 184, 0xFFFBBF24, false);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
