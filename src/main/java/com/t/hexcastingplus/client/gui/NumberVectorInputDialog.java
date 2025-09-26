package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

public class NumberVectorInputDialog extends Screen {
    private final Screen parentScreen;
    private final GuiSpellcasting staffGui;

    // Vector inputs
    private EditBox xInput;
    private EditBox yInput;
    private EditBox zInput;
    private Button vectorSendButton;

    // Number input
    private EditBox numberInput;
    private Button numberSendButton;

    // Common
    private Button cancelButton;
    private Component errorMessage = null;

    private static final int SECTION_SPACING = 35;

    public NumberVectorInputDialog(Screen parentScreen, GuiSpellcasting staffGui) {
        super(Component.literal("Number & Vector Input"));
        this.parentScreen = parentScreen;
        this.staffGui = staffGui;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // === VECTOR SECTION (top) ===
        int vectorY = centerY - 60;

        // X input field
        this.xInput = new EditBox(this.font,
                centerX - 90, vectorY, 55, 20,
                Component.literal("X"));
        this.xInput.setMaxLength(20);
        this.xInput.setHint(Component.literal("X"));
        this.xInput.setResponder(this::onVectorTextChanged);
        this.addRenderableWidget(this.xInput);

        // Y input field
        this.yInput = new EditBox(this.font,
                centerX - 27, vectorY, 55, 20,
                Component.literal("Y"));
        this.yInput.setMaxLength(20);
        this.yInput.setHint(Component.literal("Y"));
        this.addRenderableWidget(this.yInput);

        // Z input field
        this.zInput = new EditBox(this.font,
                centerX + 35, vectorY, 55, 20,
                Component.literal("Z"));
        this.zInput.setMaxLength(20);
        this.zInput.setHint(Component.literal("Z"));
        this.addRenderableWidget(this.zInput);

        // Vector send button
        this.vectorSendButton = Button.builder(
                        Component.literal("Send Vector"),
                        button -> this.sendVector())
                .pos(centerX - 40, vectorY + 25)
                .size(80, 20)
                .build();
        this.addRenderableWidget(this.vectorSendButton);

        // === NUMBER SECTION (bottom) ===
        int numberY = centerY + 10;

        // Number input field
        this.numberInput = new EditBox(this.font,
                centerX - 75, numberY, 150, 20,
                Component.literal("Number"));
        this.numberInput.setMaxLength(20);
        this.numberInput.setHint(Component.literal("0"));
        this.numberInput.setFilter(this::isValidNumberChar);
        this.addRenderableWidget(this.numberInput);

        // Number send button
        this.numberSendButton = Button.builder(
                        Component.literal("Send Number"),
                        button -> this.sendNumber())
                .pos(centerX - 40, numberY + 25)
                .size(80, 20)
                .build();
        this.addRenderableWidget(this.numberSendButton);

        // === CANCEL BUTTON (bottom) ===
        this.cancelButton = Button.builder(
                        Component.literal("Cancel"),
                        button -> this.onClose())
                .pos(centerX - 35, this.height - 35)
                .size(70, 20)
                .build();
        this.addRenderableWidget(this.cancelButton);

        // Focus the first input field
        this.setInitialFocus(this.xInput);
    }

    private void onVectorTextChanged(String text) {
        // Check if this is the first field and contains delimiters
        if (this.getFocused() == xInput && (text.contains(", ") || text.contains(",") || text.contains(" "))) {
            parseAndDistribute(text);
        }
    }

    private void parseAndDistribute(String text) {
        String[] parts;

        // Priority: ", " then "," then spaces
        if (text.contains(", ")) {
            parts = text.split(", ");
        } else if (text.contains(",")) {
            parts = text.split(",");
        } else {
            parts = text.split("\\s+");
        }

        if (parts.length >= 3) {
            // Handle ~ for each coordinate
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                xInput.setValue(parts[0].trim().equals("~") ? String.format("%.2f", player.getX()) : parts[0].trim());
                yInput.setValue(parts[1].trim().equals("~") ? String.format("%.2f", player.getY()) : parts[1].trim());
                zInput.setValue(parts[2].trim().equals("~") ? String.format("%.2f", player.getZ()) : parts[2].trim());
            } else {
                xInput.setValue(parts[0].trim());
                yInput.setValue(parts[1].trim());
                zInput.setValue(parts[2].trim());
            }
            // Move focus to vector send button
            this.setFocused(vectorSendButton);
        } else if (parts.length == 2) {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                xInput.setValue(parts[0].trim().equals("~") ? String.format("%.2f", player.getX()) : parts[0].trim());
                yInput.setValue(parts[1].trim().equals("~") ? String.format("%.2f", player.getY()) : parts[1].trim());
            } else {
                xInput.setValue(parts[0].trim());
                yInput.setValue(parts[1].trim());
            }
            this.setFocused(zInput);
        }
    }

    private boolean isValidNumberChar(String text) {
        if (text.isEmpty()) return true;
        // Allow negative numbers and decimals
        return text.matches("^-?\\d*\\.?\\d*$");
    }

    private void sendVector() {
        String xText = xInput.getValue();
        String yText = yInput.getValue();
        String zText = zInput.getValue();

        // If all blank, use player position
        if (xText.isEmpty() && yText.isEmpty() && zText.isEmpty()) {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                xText = String.format("%.2f", player.getX());
                yText = String.format("%.2f", player.getY());
                zText = String.format("%.2f", player.getZ());
            } else {
                xText = "0";
                yText = "0";
                zText = "0";
            }
        } else {
            // Handle individual ~ or empty fields
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                if (xText.equals("~") || xText.isEmpty()) xText = String.format("%.2f", player.getX());
                if (yText.equals("~") || yText.isEmpty()) yText = String.format("%.2f", player.getY());
                if (zText.equals("~") || zText.isEmpty()) zText = String.format("%.2f", player.getZ());
            } else {
                if (xText.isEmpty()) xText = "0";
                if (yText.isEmpty()) yText = "0";
                if (zText.isEmpty()) zText = "0";
            }
        }

        try {
            double x = Double.parseDouble(xText);
            double y = Double.parseDouble(yText);
            double z = Double.parseDouble(zText);

            // Close this dialog
            this.onClose();

            // Send the three numbers first
            try {
                java.lang.reflect.Method sendNumber = staffGui.getClass().getMethod("hexcastingplus$sendNumberPattern", double.class);
                sendNumber.invoke(staffGui, x);
                sendNumber.invoke(staffGui, y);
                sendNumber.invoke(staffGui, z);

                // Create and send Vector Exaltation pattern
                HexPattern vectorPattern = HexPattern.fromAngles("eqqqqq", HexDir.EAST);
                PatternDrawingHelper.addPatternViaPacket(staffGui, vectorPattern, "Vector Exaltation");

            } catch (Exception e) {
                System.err.println("Failed to send vector patterns: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (NumberFormatException e) {
            this.errorMessage = Component.literal("Invalid vector format");
        }
    }

    private void sendNumber() {
        String text = this.numberInput.getValue();

        // Default to "0" if empty
        if (text.isEmpty()) {
            text = "0";
        }

        try {
            double value = Double.parseDouble(text);

            // Close this dialog and send the pattern
            this.onClose();

            // Use reflection to call the method
            try {
                java.lang.reflect.Method method = staffGui.getClass().getMethod("hexcastingplus$sendNumberPattern", double.class);
                method.invoke(staffGui, value);
            } catch (Exception e) {
                System.err.println("Failed to invoke hexcastingplus$sendNumberPattern: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (NumberFormatException e) {
            this.errorMessage = Component.literal("Invalid number format");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // If in vector fields, send vector
            if (this.getFocused() == xInput || this.getFocused() == yInput || this.getFocused() == zInput) {
                this.sendVector();
                return true;
            }
            // If in number field, send number
            if (this.getFocused() == numberInput) {
                this.sendNumber();
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_TAB) {
            // Tab navigation between fields
            if (this.getFocused() == xInput) {
                this.setFocused(yInput);
                return true;
            } else if (this.getFocused() == yInput) {
                this.setFocused(zInput);
                return true;
            } else if (this.getFocused() == zInput) {
                this.setFocused(numberInput);
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Main title
        guiGraphics.drawCenteredString(this.font,
                Component.literal("Number & Vector Input"),
                centerX, 20, 0xFFFFFF);

        // Vector section label
        guiGraphics.drawCenteredString(this.font,
                Component.literal("Vector (X, Y, Z)"),
                centerX, centerY - 80, 0xAAAAAA);

        // Vector hint
        guiGraphics.drawCenteredString(this.font,
                Component.literal("Paste coords or use ~ for position"),
                centerX, centerY - 70, 0x606060);

        // Number section label
        guiGraphics.drawCenteredString(this.font,
                Component.literal("Number"),
                centerX, centerY - 10, 0xAAAAAA);

        // Draw error message if present
        if (this.errorMessage != null) {
            guiGraphics.drawCenteredString(this.font,
                    this.errorMessage,
                    centerX, this.height - 55, 0xFF5555);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}