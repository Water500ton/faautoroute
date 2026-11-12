package com.water500t.faautoroute;

import net.minecraft.network.chat.Component;

/** A failure that can be shown directly to the player. */
public class RoutePlanningException extends Exception {
    private final Component component;

    public RoutePlanningException(Component component) {
        super(component.getString());
        this.component = component;
    }

    public Component component() {
        return component;
    }
}
