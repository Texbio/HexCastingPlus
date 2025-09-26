package com.t.hexcastingplus.client.gui;

import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RenamePatternDialog extends Screen {
    private final Screen parent;
    private final PatternStorage.SavedPattern pattern;
    private EditBox nameInput;
    private TextFieldHistory nameInputHistory;
    private Component errorMessage = null;
    private Button renameButton;
    private boolean shouldEnableButton = false;
    private static String preservedText = "";
    private long errorMessageTime = 0;
    private static final long ERROR_DISPLAY_TIME = 2000; // 2 seconds

    public RenamePatternDialog(Screen parent, PatternStorage.SavedPattern pattern) {
        super(Component.literal("Rename Pattern"));
        this.parent = parent;
        this.pattern = pattern;
    }

    @Override
    protected void init() {
        super.init();

        // Keep GUI hidden while in this dialog
        if (this.minecraft != null && this.minecraft.options != null) {
            this.minecraft.options.hideGui = true;
        }

        int dialogWidth = 250;
        int dialogHeight = 120;
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Create buttons FIRST before setting up validation
        int buttonY = dialogTop + dialogHeight - 30;

        this.renameButton = Button.builder(Component.literal("Rename"), button -> renamePattern())
                .bounds(dialogLeft + 30, buttonY, 70, 20)
                .build();

        Button cancelButton = Button.builder(Component.literal("Cancel"), button -> this.onClose())
                .bounds(dialogLeft + dialogWidth - 100, buttonY, 70, 20)
                .build();

        // NOW set up the input field with validation (after button exists)
        this.nameInput = new EditBox(this.font, dialogLeft + 20, dialogTop + 35, dialogWidth - 40, 20,
                Component.literal("Pattern Name"));
        this.nameInput.setValue(pattern.getName());
        this.nameInput.setMaxLength(ValidationConstants.MAX_PATTERN_NAME_LENGTH);

        // Initialize history with current name
        this.nameInputHistory = new TextFieldHistory(pattern.getName());

        // Add validation filter (now renameButton exists)
        this.nameInput.setFilter(input -> {
            String trimmedInput = input.trim();

            if (trimmedInput.isEmpty()) {
                errorMessage = null;
                renameButton.active = false;
                return true;
            }

            // Block invalid characters from being typed
            if (!input.matches("^[^<>:\"|?*\\\\/]*$")) {
                // Check if it's just the last character that's invalid
                if (input.length() > 1) {
                    String withoutLast = input.substring(0, input.length() - 1);
                    if (withoutLast.matches("^[^<>:\"|?*\\\\/]*$")) {
                        // Last character was invalid, show error briefly
                        errorMessage = Component.literal("Cannot use < > : \" | ? * \\ /");
                        errorMessageTime = System.currentTimeMillis();
                        return false; // Block the invalid character
                    }
                }
                return false; // Block entirely invalid input
            }

            // Check other validations using trimmed input
            if (trimmedInput.length() > ValidationConstants.MAX_PATTERN_NAME_LENGTH) {
                errorMessage = Component.literal("Maximum " + ValidationConstants.MAX_PATTERN_NAME_LENGTH + " characters allowed");
                errorMessageTime = System.currentTimeMillis();
                renameButton.active = false;
            } else if (trimmedInput.equals(pattern.getName())) {
                errorMessage = null;
                renameButton.active = false; // No change
            } else {
                errorMessage = null;
                renameButton.active = true;
            }

            return true; // Allow valid characters
        });

        addRenderableWidget(nameInput);
        addRenderableWidget(renameButton);
        addRenderableWidget(cancelButton);
        this.setInitialFocus(nameInput);

        // Set initial focus to the input field
        this.setInitialFocus(nameInput);

        // Set initial button state
        renameButton.active = false; // Initially disabled since name hasn't changed
    }

    private void renamePattern() {
        String newName = nameInput.getValue().trim();

        // Sanitize invalid characters
        newName = newName.replaceAll("[<>:\"|?*\\\\/]", "");

        // Final validation before renaming
        if (newName.isEmpty()) {
            errorMessage = Component.literal("Pattern name cannot be empty");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        // Check if it only contains valid characters
        if (!newName.matches(ValidationConstants.PATTERN_NAME_PATTERN)) {
            errorMessage = Component.literal("Cannot use < > : \" | ? * \\ /");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        // Don't allow names that are just spaces
        if (newName.matches("^\\s+$")) {
            errorMessage = Component.literal("Pattern name must contain letters or numbers");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        // Check length
        if (newName.length() > ValidationConstants.MAX_PATTERN_NAME_LENGTH) {
            errorMessage = Component.literal("Maximum " + ValidationConstants.MAX_PATTERN_NAME_LENGTH + " characters allowed");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        // Only proceed if name actually changed
        if (!newName.equals(pattern.getName())) {
            String oldName = pattern.getName();
            String folder = pattern.getFolder();

            // Rename the file and get the actual final name (might have _2, _3, etc.)
            String finalName = PatternStorage.renamePatternFile(pattern, newName);

            if (finalName != null) {
                // Update the parent screen's cached order if name changed
                if (!finalName.equals(oldName)) {
                    if (parent instanceof PatternManagerScreen) {
                        PatternManagerScreen pmScreen = (PatternManagerScreen) parent;
                        // Only update cache, don't reload from disk
                        pmScreen.updatePatternOrderCache(folder, oldName, finalName);
                        // REMOVED: pmScreen.updatePatternList();
                    }
                }
            }
        }

        this.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (nameInputHistory != null && nameInput != null) {
            nameInputHistory.checkAndRecord(nameInput.getValue());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Right-click to clear name input
        if (button == 1 && nameInput != null && nameInput.isMouseOver(mouseX, mouseY)) {
            nameInputHistory.saveState(nameInput.getValue());
            nameInput.setValue("");
            errorMessage = null;
            renameButton.active = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
        // Simple dark gradient background, matching PatternManagerScreen
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);

        int dialogWidth = 250;
        int dialogHeight = 120;
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Dialog background
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xE0000000);

        // Border
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + 1, 0xFFFFFFFF);
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + 1, dialogTop + dialogHeight, 0xFFFFFFFF);
        guiGraphics.fill(dialogLeft + dialogWidth - 1, dialogTop, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xFFFFFFFF);
        guiGraphics.fill(dialogLeft, dialogTop + dialogHeight - 1, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xFFFFFFFF);

        // Title - show the current name (truncated if needed)
        String patternNameForTitle = pattern.getName();
        String titlePrefix = "Rename \"";
        String titleSuffix = "\"";
        int maxTitleWidth = dialogWidth - 20; // Leave 10px padding on each side
        int prefixSuffixWidth = this.font.width(titlePrefix + titleSuffix);
        int availableNameWidth = maxTitleWidth - prefixSuffixWidth;

        // Truncate pattern name if it would exceed dialog width
        if (this.font.width(patternNameForTitle) > availableNameWidth) {
            patternNameForTitle = this.font.plainSubstrByWidth(patternNameForTitle,
                    availableNameWidth - this.font.width("...")) + "...";
        }

        Component title = Component.literal(titlePrefix + patternNameForTitle + titleSuffix);
        int titleX = centerX - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title, titleX, dialogTop + 10, 0xFFFFFF);

        // Render error message if present and within display time
        if (errorMessage != null && System.currentTimeMillis() - errorMessageTime < ERROR_DISPLAY_TIME) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().scale(0.8f, 0.8f, 1.0f);
            int scaledX = (int)((dialogLeft + 20) / 0.8f);
            int scaledY = (int)((dialogTop + 65) / 0.8f);
            guiGraphics.drawString(this.font, errorMessage, scaledX, scaledY, 0xFF5555);
            guiGraphics.pose().popPose();
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // Clean up text field history to prevent memory leak
        if (nameInputHistory != null) {
            nameInputHistory.cleanup();
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Handle Escape key to close dialog
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        // Handle Ctrl+Z for undo
        if (keyCode == GLFW.GLFW_KEY_Z && hasControlDown() && !hasShiftDown()) {
            if (nameInputHistory != null) {
                String previousText = nameInputHistory.undo();
                if (previousText != null) {
                    nameInput.setValue(previousText);
                    nameInput.moveCursorToEnd();
                    // Re-trigger validation
                    validateAndUpdateButton(previousText);
                    return true;
                }
            }
        }

        // Handle Ctrl+Y or Ctrl+Shift+Z for redo
        if ((keyCode == GLFW.GLFW_KEY_Y && hasControlDown()) ||
                (keyCode == GLFW.GLFW_KEY_Z && hasControlDown() && hasShiftDown())) {
            if (nameInputHistory != null) {
                String redoText = nameInputHistory.redo();
                if (redoText != null) {
                    nameInput.setValue(redoText);
                    nameInput.moveCursorToEnd();
                    // Re-trigger validation
                    validateAndUpdateButton(redoText);
                    return true;
                }
            }
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && nameInput != null && nameInput.isFocused()) {
            String newName = nameInput.getValue().trim();
            // If there's a change, process it
            if (!newName.isEmpty() && !newName.equals(pattern.getName()) && errorMessage == null) {
                renamePattern();
            } else {
                // No change, just close like cancel
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (nameInput != null) {
            preservedText = nameInput.getValue();
        }
        super.resize(minecraft, width, height);
        if (nameInput != null && !preservedText.isEmpty()) {
            nameInput.setValue(preservedText);
            preservedText = "";
        }
    }

    private void validateAndUpdateButton(String text) {
        if (text.isEmpty() || text.trim().equals(pattern.getName())) {
            errorMessage = null;
            renameButton.active = false;
        } else if (!text.matches(ValidationConstants.PATTERN_NAME_PATTERN)) {
            errorMessage = Component.literal("Cannot use < > : \" | ? * \\ /");
            renameButton.active = false;
        } else if (text.trim().isEmpty()) {
            errorMessage = Component.literal("Name cannot be only spaces");
            renameButton.active = false;
        } else if (text.trim().length() > ValidationConstants.MAX_PATTERN_NAME_LENGTH) {
            errorMessage = Component.literal("Maximum " + ValidationConstants.MAX_PATTERN_NAME_LENGTH + " characters allowed");
            renameButton.active = false;
        } else {
            errorMessage = null;
            renameButton.active = true;
        }
    }
}