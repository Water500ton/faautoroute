package com.water500t.faautoroute;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A small paged picker for the current world's Xaero waypoints. */
public class XaeroWaypointScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 230;
    private static final int PAGE_SIZE = 8;

    private final Screen parent;
    private final Consumer<XaeroIntegration.XaeroWaypoint> onSelect;
    private final List<XaeroIntegration.XaeroWaypoint> waypoints;
    private int page;
    private int left;
    private int top;
    private int panelWidth;

    public XaeroWaypointScreen(Screen parent, Consumer<XaeroIntegration.XaeroWaypoint> onSelect) {
        super(Component.translatable("gui.faautoroute.xaero.title"));
        this.parent = parent;
        this.onSelect = onSelect;
        this.waypoints = new ArrayList<>(XaeroIntegration.readWaypoints());
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(PANEL_WIDTH, this.width - 8);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - PANEL_HEIGHT) / 2;

        int pages = Math.max(1, (this.waypoints.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(this.page, pages - 1));
        int start = this.page * PAGE_SIZE;
        int end = Math.min(this.waypoints.size(), start + PAGE_SIZE);

        for (int i = start; i < end; i++) {
            XaeroIntegration.XaeroWaypoint waypoint = this.waypoints.get(i);
            String label = shorten(waypoint.name() + "  (" + waypoint.x() + ", " + waypoint.z() + ")");
            this.addRenderableWidget(Button.builder(Component.literal(label), button -> {
                this.onSelect.accept(waypoint);
                this.onClose();
            }).bounds(this.left + 20, this.top + 34 + (i - start) * 20, this.panelWidth - 40, 18).build());
        }

        int bottom = this.top + PANEL_HEIGHT - 28;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.xaero.prev"), button -> {
            this.page--;
            this.rebuildWidgets();
        }).bounds(this.left + 20, bottom, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.xaero.next"), button -> {
            this.page++;
            this.rebuildWidgets();
        }).bounds(this.left + 86, bottom, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.faautoroute.done"), button -> this.onClose())
                .bounds(this.left + this.panelWidth - 80, bottom, 60, 18).build());
    }

    private String shorten(String value) {
        return this.font.plainSubstrByWidth(value, this.panelWidth - 48);
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
        int pages = Math.max(1, (this.waypoints.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.drawString(this.font, Component.translatable("gui.faautoroute.xaero.page", this.page + 1, pages),
                this.left + 20, this.top + 20, 0xFF94A3B8, false);

        if (this.waypoints.isEmpty()) {
            List<FormattedCharSequence> lines = this.font.split(Component.translatable("gui.faautoroute.xaero.empty"), this.panelWidth - 40);
            int y = this.top + 80;
            for (FormattedCharSequence line : lines) {
                int lineWidth = this.font.width(line);
                graphics.drawString(this.font, line, this.left + (this.panelWidth - lineWidth) / 2, y, 0xFF94A3B8, false);
                y += 10;
            }
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
