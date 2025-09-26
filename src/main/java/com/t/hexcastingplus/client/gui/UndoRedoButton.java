package com.t.hexcastingplus.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public class UndoRedoButton extends Button {
    private final Component tooltip;
    private final boolean isUndo;

    public UndoRedoButton(int x, int y, int width, int height, Component text, OnPress onPress, Component tooltip, boolean isUndo) {
        super(x, y, width, height, text, onPress, DEFAULT_NARRATION);
        this.tooltip = tooltip;
        this.isUndo = isUndo;
        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Render button background
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

        // Draw symbol 2x larger
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(getX() + width/2.0, getY() + height/2.0, 0);
        guiGraphics.pose().scale(2.0f, 2.0f, 1.0f);

        String symbol = isUndo ? "↶" : "↷";
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, symbol, 0, -4,
                this.active ? 0xFFFFFF : 0x808080);

        guiGraphics.pose().popPose();
    }
}