package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class SavePatternDialog extends Screen {
    private final Screen parent;
    private final String currentFolder;
    private EditBox nameInput;
    private Button saveButton;
    private Button cancelButton;
    private Component errorMessage = null;
    private long errorMessageTime = 0;
    private static final long ERROR_DISPLAY_TIME = 2000; // 2 seconds
    private TextFieldHistory nameInputHistory;
    private boolean shouldEnableButton = true;
    private static String preservedText = "";


    // Pattern preview area
    private final List<HexPattern> patterns;

    //Other
    private final GuiSpellcasting staffGui;

    public SavePatternDialog(Screen parent, String currentFolder, GuiSpellcasting staffGui) {
        super(Component.literal("Save Pattern"));
        this.parent = parent;
        this.currentFolder = currentFolder;
        this.staffGui = staffGui;

        // Get current patterns from staff using backup5's method
        this.patterns = getTrackedPatterns();
    }

    private List<HexPattern> getTrackedPatterns() {
        return com.t.hexcastingplus.common.pattern.PatternCache.getMergedPatterns();
    }

    @Override
    protected void init() {
        super.init();

        int dialogWidth = 300;
        int dialogHeight = 160; // Reduced height since no checkbox
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Pattern name input with validation
        this.nameInput = new EditBox(this.font, dialogLeft + 20, dialogTop + 40, dialogWidth - 40, 20,
                Component.literal("Pattern Name"));

        // Generate default name
        String defaultName = PatternStorage.generateNextPatternName(currentFolder);
        this.nameInput.setValue(defaultName);
        this.nameInput.setMaxLength(ValidationConstants.MAX_PATTERN_NAME_LENGTH); // Enforce max length in UI

        // Initialize history with default name
        this.nameInputHistory = new TextFieldHistory(defaultName);

        // Add validation filter
        this.nameInput.setFilter(input -> {
            String trimmedInput = input.trim();

            if (trimmedInput.isEmpty()) {
                errorMessage = null;
                shouldEnableButton = false;
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
                shouldEnableButton = false;
            } else {
                errorMessage = null;
                shouldEnableButton = true;
            }

            // Update button if it exists
            if (saveButton != null) {
                saveButton.active = shouldEnableButton;
            }

            return true; // Allow valid characters
        });

        addRenderableWidget(nameInput);

        // NO CHECKBOX - stacks cannot be saved

        // Buttons
        int buttonY = dialogTop + dialogHeight - 40;
        this.saveButton = Button.builder(Component.literal("Save"), button -> savePattern())
                .bounds(dialogLeft + 20, buttonY, 80, 20)
                .build();

        // Apply initial state
        saveButton.active = shouldEnableButton;

        this.cancelButton = Button.builder(Component.literal("Cancel"), button -> this.onClose())
                .bounds(dialogLeft + dialogWidth - 100, buttonY, 80, 20)
                .build();

        addRenderableWidget(saveButton);
        addRenderableWidget(cancelButton);
        this.setInitialFocus(nameInput);
    }

    private void savePattern() {
        String name = nameInput.getValue().trim();

        // Sanitize invalid characters instead of rejecting
        name = name.replaceAll("[<>:\"|?*\\\\/]", "");

        // Final validation before saving
        if (name.isEmpty()) {
            errorMessage = Component.literal("Pattern name cannot be empty");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        if (!name.matches(ValidationConstants.PATTERN_NAME_PATTERN)) {
            errorMessage = Component.literal("Cannot use < > : \" | ? * \\ /");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        if (name.matches("^\\s+$")) {
            errorMessage = Component.literal("Pattern name must contain letters or numbers");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        if (name.startsWith(".")) {
            errorMessage = Component.literal("Pattern name cannot start with a dot");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        if (name.length() > ValidationConstants.MAX_PATTERN_NAME_LENGTH) {
            errorMessage = Component.literal("Maximum " + ValidationConstants.MAX_PATTERN_NAME_LENGTH + " characters allowed");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        // ==================== DEBUGGING BLOCK ====================
        boolean isFromCasted = com.t.hexcastingplus.common.pattern.PatternCache.isLoadedFromCasted();
        int currentPatternCount = patterns.size();
        int loadedPatternCount = com.t.hexcastingplus.common.pattern.PatternCache.getLoadedPatternCount();

        if (ValidationConstants.DEBUG) {
            System.out.println("[HexCasting+] DEBUG: Save Dialog Check");
            System.out.println("  - Is Loaded From Casted? " + isFromCasted);
            System.out.println("  - Current Patterns in Dialog: " + currentPatternCount);
            System.out.println("  - Original Loaded Pattern Count: " + loadedPatternCount);
            System.out.println("  - Condition Met? " + (isFromCasted && currentPatternCount == loadedPatternCount));
        }

        // ========================================================

        // Check if these are unmodified casted patterns
        if (isFromCasted && currentPatternCount == loadedPatternCount) {
            errorMessage = Component.literal("These patterns are already saved in Casted");
            errorMessageTime = System.currentTimeMillis();
            return;
        }

        // ONLY save patterns - no stack functionality
        if (!patterns.isEmpty()) {
            PatternStorage.savePattern(name, patterns, currentFolder);

            com.t.hexcastingplus.common.pattern.PatternCache.clearLoadedFromCastedFlag();

            if (parent instanceof PatternManagerScreen) {
                ((PatternManagerScreen) parent).onPatternSaved(name);
            }
        }

        this.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark background
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);

        int dialogWidth = 300;
        int dialogHeight = 160;
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Dialog background
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xE0000000);
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + 1, 0xFFFFFFFF);
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + 1, dialogTop + dialogHeight, 0xFFFFFFFF);
        guiGraphics.fill(dialogLeft + dialogWidth - 1, dialogTop, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xFFFFFFFF);
        guiGraphics.fill(dialogLeft, dialogTop + dialogHeight - 1, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xFFFFFFFF);

        // Title
        Component title = Component.literal("Save Patterns");
        int titleX = centerX - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title, titleX, dialogTop + 10, 0xFFFFFF);

        // Pattern count info - ONLY PATTERNS
        String patternInfo = patterns.size() + " pattern(s) to save";
        int infoX = centerX - this.font.width(patternInfo) / 2;
        guiGraphics.drawString(this.font, patternInfo, infoX, dialogTop + 80, 0xFF808080);

        // Render error message if present and within display time
        if (errorMessage != null && System.currentTimeMillis() - errorMessageTime < ERROR_DISPLAY_TIME) {
            // Render with smaller font (scale 0.8 = ~8pt from 10pt base)
            guiGraphics.pose().pushPose();
            guiGraphics.pose().scale(0.8f, 0.8f, 1.0f);
            int scaledX = (int)((dialogLeft + 20) / 0.8f);
            int scaledY = (int)((dialogTop + 105) / 0.8f);
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
    public void tick() {
        super.tick();
        if (nameInputHistory != null && nameInput != null) {
            nameInputHistory.checkAndRecord(nameInput.getValue());
        }

        // Clear error message after timeout
        if (errorMessage != null && System.currentTimeMillis() - errorMessageTime > ERROR_DISPLAY_TIME) {
            errorMessage = null;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Right-click to clear name input
        if (button == 1 && nameInput != null && nameInput.isMouseOver(mouseX, mouseY)) {
            nameInputHistory.saveState(nameInput.getValue());
            nameInput.setValue("");
            errorMessage = null;
            saveButton.active = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
                    if (!previousText.isEmpty() && previousText.matches(ValidationConstants.PATTERN_NAME_PATTERN) &&
                            !previousText.trim().isEmpty() && previousText.trim().length() <= ValidationConstants.MAX_PATTERN_NAME_LENGTH) {
                        errorMessage = null;
                        saveButton.active = true;
                    }
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
                    if (!redoText.isEmpty() && redoText.matches(ValidationConstants.PATTERN_NAME_PATTERN) &&
                            !redoText.trim().isEmpty() && redoText.trim().length() <= ValidationConstants.MAX_PATTERN_NAME_LENGTH) {
                        errorMessage = null;
                        saveButton.active = true;
                    }
                    return true;
                }
            }
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && nameInput != null && nameInput.isFocused()) {
            String name = nameInput.getValue().trim();
            // If there's a valid name, save; otherwise just close
            if (!name.isEmpty() && errorMessage == null) {
                savePattern();
            } else if (name.isEmpty()) {
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
}