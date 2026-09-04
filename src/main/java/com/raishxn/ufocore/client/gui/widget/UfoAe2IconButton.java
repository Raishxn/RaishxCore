package com.raishxn.ufocore.client.gui.widget;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** AE2 toolbar button with an icon selected by the consuming addon. */
public final class UfoAe2IconButton extends IconButton {
    private final Icon icon;

    public UfoAe2IconButton(Icon icon, Component tooltip, OnPress onPress) {
        super(onPress);
        this.icon = Objects.requireNonNull(icon, "icon");
        setMessage(Objects.requireNonNull(tooltip, "tooltip"));
    }

    @Override
    protected Icon getIcon() {
        return this.icon;
    }
}
