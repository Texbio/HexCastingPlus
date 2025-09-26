package com.t.hexcastingplus.client.gui;

import com.t.hexcastingplus.client.gui.TextFieldHistory;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class FolderSelectorScreen extends Screen {
    private final Screen parent;
    private final PatternStorage.SavedPattern pattern;
    private final List<String> availableFolders;

    private FolderListWidget folderList;
    private EditBox newFolderInput;
    private Button moveButton;
    private Button createFolderButton;
    private Button deleteButton;
    private Button renameButton;

    private TextFieldHistory newFolderInputHistory;

    private boolean weSetHideGui = false;

    // field to track if folders were modified
    private boolean foldersModified = false;

    //valid folder name
    private Component errorMessage = null;

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
        // Simple dark gradient background, matching PatternManagerScreen
        // This overrides the default dirt texture background
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    public FolderSelectorScreen(Screen parent, PatternStorage.SavedPattern pattern, List<String> availableFolders) {
        super(Component.literal("Move Pattern"));
        this.parent = parent;
        this.pattern = pattern;

        // Keep all folders including default for display
        // The list should already have default first from PatternStorage.getAvailableFolders()
        this.availableFolders = new ArrayList<>(availableFolders);

        // Ensure default is always first
        this.availableFolders.remove("default");
        this.availableFolders.add(0, "default");

        // Remove special folders (trash, casted)
        this.availableFolders.removeIf(folder -> folder.startsWith(".") && !folder.equals("default"));
    }

    @Override
    protected void init() {
        super.init();

        // Keep GUI hidden while in this dialog, set hideGui if parent hasn't already done it
        if (this.minecraft != null && this.minecraft.options != null) {
            if (!this.minecraft.options.hideGui) {
                this.minecraft.options.hideGui = true;
                this.weSetHideGui = true;
            }
        }

        int dialogWidth = 300;
        int dialogHeight = 250;
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Folder list
        this.folderList = new FolderListWidget(this.minecraft, dialogWidth - 40, 120,
                dialogTop + 40, dialogTop + 160, 20);
        addRenderableWidget(folderList);

        // New folder input with validation
        this.newFolderInput = new EditBox(this.font, dialogLeft + 20, dialogTop + 170, dialogWidth - 120, 20,
                Component.literal("New Folder Name"));
        this.newFolderInput.setHint(Component.literal("New folder"));
        this.newFolderInput.setMaxLength(ValidationConstants.MAX_FOLDER_NAME_LENGTH);

        // Initialize history for new folder input
        this.newFolderInputHistory = new TextFieldHistory("");

        this.newFolderInput.setFilter(input -> {
            if (input.isEmpty()) {
                errorMessage = null;
                return true;
            }

            // Check for valid characters
            boolean valid = input.matches(ValidationConstants.FOLDER_NAME_PATTERN);
            if (!valid) {
                errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
            } else if (availableFolders.contains(input.trim())) {
                errorMessage = Component.literal("Folder already exists");
            } else {
                errorMessage = null;
            }
            return valid;
        });
        addRenderableWidget(newFolderInput);

        // Create folder button
        this.createFolderButton = Button.builder(Component.literal("Create"), button -> createNewFolder())
                .bounds(dialogLeft + dialogWidth - 90, dialogTop + 170, 70, 20)
                .build();
        addRenderableWidget(createFolderButton);

        // Bottom buttons
        int buttonY = dialogTop + dialogHeight - 30;
        int buttonWidth = 60;

        this.moveButton = Button.builder(Component.literal("Move"), button -> movePattern())
                .bounds(dialogLeft + 20, buttonY, buttonWidth, 20)
                .build();

        this.deleteButton = Button.builder(Component.literal("Delete"), button -> deleteSelectedFolder())
                .bounds(dialogLeft + 30 + buttonWidth, buttonY, buttonWidth, 20)
                .build();

        this.renameButton = Button.builder(Component.literal("Rename"), button -> renameSelectedFolder())
                .bounds(dialogLeft + 40 + buttonWidth * 2, buttonY, buttonWidth, 20)
                .build();

        // Add a close button (X) in the top right corner of the dialog
        Button closeButton = Button.builder(Component.literal("×"), button -> this.onClose())
                .bounds(dialogLeft + dialogWidth - 25, dialogTop + 5, 20, 20)
                .build();

        addRenderableWidget(moveButton);
        addRenderableWidget(deleteButton);
        addRenderableWidget(renameButton);
        addRenderableWidget(closeButton);

        updateMoveButton();
    }

    @Override
    public void tick() {
        super.tick();
        if (newFolderInputHistory != null && newFolderInput != null) {
            newFolderInputHistory.checkAndRecord(newFolderInput.getValue());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Right-click to clear new folder input
        if (button == 1 && newFolderInput != null && newFolderInput.isMouseOver(mouseX, mouseY)) {
            newFolderInputHistory.saveState(newFolderInput.getValue());
            newFolderInput.setValue("");
            errorMessage = null;
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
            if (newFolderInput != null && newFolderInput.isFocused() && newFolderInputHistory != null) {
                String previousText = newFolderInputHistory.undo();
                if (previousText != null) {
                    newFolderInput.setValue(previousText);
                    newFolderInput.moveCursorToEnd();
                    // Re-validate
                    if (previousText.isEmpty()) {
                        errorMessage = null;
                    } else if (!previousText.matches(ValidationConstants.FOLDER_NAME_PATTERN)) {
                        errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
                    } else if (availableFolders.contains(previousText.trim())) {
                        errorMessage = Component.literal("Folder already exists");
                    } else {
                        errorMessage = null;
                    }
                    return true;
                }
            }
        }

        // Handle Ctrl+Y or Ctrl+Shift+Z for redo
        if ((keyCode == GLFW.GLFW_KEY_Y && hasControlDown()) ||
                (keyCode == GLFW.GLFW_KEY_Z && hasControlDown() && hasShiftDown())) {
            if (newFolderInput != null && newFolderInput.isFocused() && newFolderInputHistory != null) {
                String redoText = newFolderInputHistory.redo();
                if (redoText != null) {
                    newFolderInput.setValue(redoText);
                    newFolderInput.moveCursorToEnd();
                    // Re-validate
                    if (redoText.isEmpty()) {
                        errorMessage = null;
                    } else if (!redoText.matches(ValidationConstants.FOLDER_NAME_PATTERN)) {
                        errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
                    } else if (availableFolders.contains(redoText.trim())) {
                        errorMessage = Component.literal("Folder already exists");
                    } else {
                        errorMessage = null;
                    }
                    return true;
                }
            }
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && newFolderInput != null && newFolderInput.isFocused()) {
            String folderName = newFolderInput.getValue().trim();
            if (!folderName.isEmpty() && !availableFolders.contains(folderName)) {
                createNewFolder();
            }
            // Always consume ENTER when input is focused (even if no folder created)
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void createNewFolder() {
        String folderName = newFolderInput.getValue().trim();

        // Validate the folder name
        if (folderName.isEmpty()) {
            errorMessage = Component.literal("Folder name cannot be empty");
            return;
        }

        // Check if it only contains valid characters
        if (!folderName.matches(ValidationConstants.FOLDER_NAME_PATTERN)) {
            errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
            return;
        }

        // Don't allow folder names that are just dots or spaces
        if (folderName.matches("^[\\.\\s]+$")) {
            errorMessage = Component.literal("Folder name must contain letters or numbers");
            return;
        }

        // Don't allow folder names starting with dot (hidden folders)
        if (folderName.startsWith(".")) {
            errorMessage = Component.literal("Folder name cannot start with a dot");
            return;
        }

        // Check length
        if (folderName.length() > ValidationConstants.MAX_FOLDER_NAME_LENGTH) {
            errorMessage = Component.literal("Maximum " + ValidationConstants.MAX_FOLDER_NAME_LENGTH + " characters allowed");
            return;
        }

        // Check if already exists
        if (availableFolders.contains(folderName)) {
            errorMessage = Component.literal("Folder already exists");
            return;
        }

        // All validations passed, create the folder
        PatternStorage.createFolder(folderName);
        availableFolders.add(folderName);
        folderList.updateFolders(availableFolders);
        newFolderInput.setValue("");
        errorMessage = null;

        // Mark that folders were modified
        foldersModified = true;

        // Select the newly created folder
        folderList.setSelected(folderName);
        updateMoveButton();

        // Notify parent immediately if it's PatternManagerScreen
        if (parent instanceof PatternManagerScreen) {
            ((PatternManagerScreen) parent).refreshFolderList();
        }
    }

    private void movePattern() {
        String selectedFolder = folderList.getSelectedFolder();
        if (selectedFolder != null && !selectedFolder.equals(pattern.getFolder())) {
            PatternStorage.movePattern(pattern, selectedFolder, null);
            this.onClose();
        }
    }

    private void deleteSelectedFolder() {
        String selectedFolder = folderList.getSelectedFolder();
        if (selectedFolder == null || selectedFolder.equals("default")) {
            return;
        }

        List<PatternStorage.SavedPattern> patternsInFolder = PatternStorage.getSavedPatterns(selectedFolder);

        if (!patternsInFolder.isEmpty()) {
            // Show confirmation dialog - pass the parent notification to it
            this.minecraft.setScreen(new DeleteFolderConfirmationScreen(this, selectedFolder, patternsInFolder.size()));
        } else {
            // Delete empty folder immediately
            PatternStorage.deleteFolder(selectedFolder);
            availableFolders.remove(selectedFolder);
            folderList.updateFolders(availableFolders);
            updateButtonStates();

            // Mark as modified and notify parent
            foldersModified = true;
            if (parent instanceof PatternManagerScreen) {
                ((PatternManagerScreen) parent).refreshFolderList();
            }
        }
    }

    private void updateButtonStates() {
        String selectedFolder = folderList.getSelectedFolder();
        boolean hasSelection = selectedFolder != null;
        boolean isNotDefault = hasSelection && !selectedFolder.equals("default");

        // Move button is active if a folder is selected and it's different from current
        moveButton.active = hasSelection && !selectedFolder.equals(pattern.getFolder());

        // Delete and Rename buttons are active if a non-default folder is selected
        deleteButton.active = isNotDefault;
        renameButton.active = isNotDefault;
    }

    private void renameSelectedFolder() {
        String selectedFolder = folderList.getSelectedFolder();
        if (selectedFolder == null || selectedFolder.equals("default")) {
            return;
        }

        // Show rename dialog
        this.minecraft.setScreen(new RenameFolderDialog(this, selectedFolder));
    }

    private void updateMoveButton() {
        String selectedFolder = folderList.getSelectedFolder();
        moveButton.active = selectedFolder != null && !selectedFolder.equals(pattern.getFolder());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Call renderBackground first (which now uses our custom gradient)
        this.renderBackground(guiGraphics);

        int dialogWidth = 300;
        int dialogHeight = 250;
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
        Component title = Component.literal("Move \"" + pattern.getName() + "\" to folder:");
        int titleX = centerX - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title, titleX, dialogTop + 10, 0xFFFFFF);

        // Render error message if present
        if (errorMessage != null) {
            guiGraphics.drawString(this.font, errorMessage, dialogLeft + 20, dialogTop + 195, 0xFF5555);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // restore hideGui if we were the ones who set it
        if (weSetHideGui && this.minecraft != null && this.minecraft.options != null) {
            this.minecraft.options.hideGui = false;
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class FolderListWidget extends ObjectSelectionList<FolderListWidget.FolderEntry> {

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // Completely override render to skip ALL background rendering
            // Just render the list items and scrollbar

            // Render the list items
            this.renderList(guiGraphics, mouseX, mouseY, partialTick);

            // Render the scrollbar if needed
            int maxScroll = this.getMaxScroll();
            if (maxScroll > 0) {
                int scrollbarX = this.getScrollbarPosition();
                int scrollbarWidth = 6;

                // Scrollbar background
                guiGraphics.fill(scrollbarX, this.y0, scrollbarX + scrollbarWidth, this.y1, 0x80000000);

                // Scrollbar handle
                int scrollbarHeight = (int)((float)((this.y1 - this.y0) * (this.y1 - this.y0)) / (float)this.getMaxPosition());
                scrollbarHeight = Math.max(32, Math.min(scrollbarHeight, this.y1 - this.y0 - 8));
                int scrollbarY = (int)this.getScrollAmount() * (this.y1 - this.y0 - scrollbarHeight) / maxScroll + this.y0;

                guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, 0x80808080);
                guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth - 1, scrollbarY + scrollbarHeight - 1, 0x80C0C0C0);
            }
        }

        @Override
        protected void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // Don't render any background - just render the entries
            int itemCount = this.getItemCount();

            for(int i = 0; i < itemCount; ++i) {
                int rowTop = this.getRowTop(i);
                int rowBottom = rowTop + this.itemHeight;

                if (rowBottom >= this.y0 && rowTop <= this.y1) {
                    FolderEntry entry = this.getEntry(i);
                    boolean isHovered = this.isMouseOver(mouseX, mouseY) &&
                            java.util.Objects.equals(this.getEntryAtPosition(mouseX, mouseY), entry);

                    entry.render(guiGraphics, i, rowTop, this.getRowLeft(), this.getRowWidth(),
                            this.itemHeight, mouseX, mouseY, isHovered, partialTick);
                }
            }
        }

        @Override
        public int getRowLeft() {
            return this.x0;
        }

        @Override
        public int getRowWidth() {
            return this.width;  // Use full width
        }

        public FolderListWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
            super(minecraft, width, height, y0, y1, itemHeight);

            // Fix positioning - center the widget horizontally
            int centerX = minecraft.getWindow().getGuiScaledWidth() / 2;
            this.setLeftPos(centerX - width / 2);

            updateFolders(availableFolders);
        }

        public void updateFolders(List<String> folders) {
            this.clearEntries();
            for (String folder : folders) {
                this.addEntry(new FolderEntry(folder));
            }
        }

        public String getSelectedFolder() {
            FolderEntry selected = this.getSelected();
            return selected != null ? selected.folderName : null;
        }

        public void setSelected(String folderName) {
            for (FolderEntry entry : this.children()) {
                if (entry.folderName.equals(folderName)) {
                    this.setSelected(entry);
                    break;
                }
            }
        }

        @Override
        protected int getScrollbarPosition() {
            return this.x0 + this.width - 6;
        }

        private class FolderEntry extends ObjectSelectionList.Entry<FolderEntry> {
            private final String folderName;

            public FolderEntry(String folderName) {
                this.folderName = folderName;
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {

                // Background with different highlights for hover vs selected
                boolean isSelected = this == FolderListWidget.this.getSelected();

                if (isSelected) {
                    // Brighter highlight for selected item
                    guiGraphics.fill(x, y, x + entryWidth, y + entryHeight, 0xA0FFFFFF);
                } else if (hovered) {
                    // Darker/less opaque highlight for hover only
                    guiGraphics.fill(x, y, x + entryWidth, y + entryHeight, 0x40FFFFFF);
                }

                // Rest of the rendering code remains the same...
                // Calculate available width for folder name dynamically
                String currentIndicator = "(current)";
                int currentIndicatorWidth = minecraft.font.width(currentIndicator) + 10; // Dynamic width + padding
                int maxNameWidth = entryWidth - 10 - currentIndicatorWidth; // 5px padding on each side

                // Truncate folder name to fit within available width
                String displayName = folderName;
                boolean isTruncated = false;

                // Use font width to properly truncate
                if (minecraft.font.width(folderName) > maxNameWidth) {
                    displayName = minecraft.font.plainSubstrByWidth(folderName, maxNameWidth - minecraft.font.width("...")) + "...";
                    isTruncated = true;
                }

                // Calculate vertical center for text
                int textY = y + (entryHeight - minecraft.font.lineHeight) / 2;

                // Folder name
                guiGraphics.drawString(minecraft.font, displayName, x + 5, textY, 0xFFFFFF);

                // Current folder indicator
                if (folderName.equals(pattern.getFolder())) {
                    int indicatorX = x + entryWidth - currentIndicatorWidth + 5; // Position dynamically
                    guiGraphics.drawString(minecraft.font, currentIndicator, indicatorX, textY, 0xFF808080);
                }

                // Render tooltip for full name if truncated and hovered
                if (isTruncated && hovered) {
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(0, 0, 400); // Bring tooltip to front
                    guiGraphics.renderTooltip(minecraft.font, Component.literal(folderName), mouseX, mouseY);
                    guiGraphics.pose().popPose();
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    FolderListWidget.this.setSelected(this);
                    updateMoveButton();
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal("Folder: " + folderName);
            }
        }
    }

    private static class DeleteFolderConfirmationScreen extends Screen {
        private final Screen parent;
        private final String folderName;
        private final int patternCount;

        public DeleteFolderConfirmationScreen(Screen parent, String folderName, int patternCount) {
            super(Component.literal("Confirm Delete"));
            this.parent = parent;
            this.folderName = folderName;
            this.patternCount = patternCount;
        }

        @Override
        protected void init() {
            super.init();

            int dialogWidth = 300;
            int dialogHeight = 120;
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            int dialogLeft = centerX - dialogWidth / 2;
            int dialogTop = centerY - dialogHeight / 2;

            // Confirm and Cancel buttons
            int buttonY = dialogTop + dialogHeight - 30;

            Button confirmButton = Button.builder(Component.literal("Delete"), button -> {
                // Move all patterns to trash
                List<PatternStorage.SavedPattern> patterns = PatternStorage.getSavedPatterns(folderName);
                for (PatternStorage.SavedPattern pattern : patterns) {
                    PatternStorage.movePattern(pattern, PatternStorage.TRASH_FOLDER, null);
                }

                // Delete the folder
                PatternStorage.deleteFolder(folderName);

                // Return to parent and refresh
                if (parent instanceof FolderSelectorScreen) {
                    FolderSelectorScreen folderScreen = (FolderSelectorScreen) parent;
                    folderScreen.availableFolders.remove(folderName);
                    folderScreen.folderList.updateFolders(folderScreen.availableFolders);
                    folderScreen.updateButtonStates();
                    folderScreen.foldersModified = true; // Mark as modified

                    // Notify the PatternManagerScreen if it's the grandparent
                    if (folderScreen.parent instanceof PatternManagerScreen) {
                        ((PatternManagerScreen) folderScreen.parent).refreshFolderList();
                    }
                }
                this.minecraft.setScreen(parent);
            }).bounds(dialogLeft + 50, buttonY, 80, 20).build();

            Button cancelButton = Button.builder(Component.literal("Cancel"),
                            button -> this.minecraft.setScreen(parent))
                    .bounds(dialogLeft + dialogWidth - 130, buttonY, 80, 20)
                    .build();

            addRenderableWidget(confirmButton);
            addRenderableWidget(cancelButton);
        }

        @Override
        public void renderBackground(GuiGraphics guiGraphics) {
            // Match the parent screen's background
            guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        }

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(guiGraphics);

            int dialogWidth = 300;
            int dialogHeight = 120;
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            int dialogLeft = centerX - dialogWidth / 2;
            int dialogTop = centerY - dialogHeight / 2;

            // Dialog background
            guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth,
                    dialogTop + dialogHeight, 0xE0000000);

            // Border
            guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + 1, 0xFFFFFFFF);
            guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + 1, dialogTop + dialogHeight, 0xFFFFFFFF);
            guiGraphics.fill(dialogLeft + dialogWidth - 1, dialogTop, dialogLeft + dialogWidth,
                    dialogTop + dialogHeight, 0xFFFFFFFF);
            guiGraphics.fill(dialogLeft, dialogTop + dialogHeight - 1, dialogLeft + dialogWidth,
                    dialogTop + dialogHeight, 0xFFFFFFFF);

            // Warning message
            Component line1 = Component.literal("Delete folder \"" + folderName + "\"?");
            Component line2 = Component.literal("This folder contains " + patternCount + " pattern(s).");
            Component line3 = Component.literal("All patterns will be moved to trash.");

            int textY = dialogTop + 20;
            guiGraphics.drawCenteredString(this.font, line1, centerX, textY, 0xFFFFFF);
            guiGraphics.drawCenteredString(this.font, line2, centerX, textY + 15, 0xFFFF00);
            guiGraphics.drawCenteredString(this.font, line3, centerX, textY + 30, 0xFF8080);

            super.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    // Replace the entire RenameFolderDialog inner class in FolderSelectorScreen.java:

    // Replace the entire RenameFolderDialog inner class in FolderSelectorScreen.java:

    private static class RenameFolderDialog extends Screen {
        private final FolderSelectorScreen parent;
        private final String oldFolderName;
        private EditBox nameInput;
        private Component errorMessage = null;
        private TextFieldHistory nameInputHistory;
        private Button renameButton;

        public RenameFolderDialog(FolderSelectorScreen parent, String oldFolderName) {
            super(Component.literal("Rename Folder"));
            this.parent = parent;
            this.oldFolderName = oldFolderName;
        }

        @Override
        protected void init() {
            super.init();

            int dialogWidth = 250;
            int dialogHeight = 120; // Increased for error message
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            int dialogLeft = centerX - dialogWidth / 2;
            int dialogTop = centerY - dialogHeight / 2;

            // Name input field with validation
            this.nameInput = new EditBox(this.font, dialogLeft + 20, dialogTop + 35,
                    dialogWidth - 40, 20, Component.literal("Folder Name"));
            this.nameInput.setValue(oldFolderName);
            this.nameInput.setMaxLength(ValidationConstants.MAX_FOLDER_NAME_LENGTH);

            // Initialize history with old folder name
            this.nameInputHistory = new TextFieldHistory(oldFolderName);

            this.nameInput.setFilter(input -> {
                if (input.isEmpty()) {
                    errorMessage = null;
                    if (renameButton != null) renameButton.active = false;
                    return true;
                }

                // Check for valid characters
                boolean valid = input.matches(ValidationConstants.FOLDER_NAME_PATTERN);
                if (!valid) {
                    errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
                    if (renameButton != null) renameButton.active = false;
                } else if (parent.availableFolders.contains(input.trim()) && !input.trim().equals(oldFolderName)) {
                    errorMessage = Component.literal("Folder already exists");
                    if (renameButton != null) renameButton.active = false;
                } else if (input.trim().equals(oldFolderName)) {
                    errorMessage = null;
                    if (renameButton != null) renameButton.active = false; // No change
                } else {
                    errorMessage = null;
                    if (renameButton != null) renameButton.active = true;
                }
                return valid;
            });
            addRenderableWidget(nameInput);

            // Buttons
            int buttonY = dialogTop + dialogHeight - 30;

            this.renameButton = Button.builder(Component.literal("Rename"), button -> {
                String newName = nameInput.getValue().trim();

                // Validate the new name
                if (!newName.isEmpty() &&
                        !newName.equals(oldFolderName) &&
                        newName.matches(ValidationConstants.FOLDER_NAME_PATTERN) &&
                        !newName.matches("^[\\.\\s]+$") &&  // Not just dots/spaces
                        !newName.startsWith(".") &&  // Not hidden folder
                        newName.length() <= ValidationConstants.MAX_FOLDER_NAME_LENGTH &&
                        !parent.availableFolders.contains(newName)) {

                    // Rename the folder
                    PatternStorage.renameFolder(oldFolderName, newName);

                    // Update the folder list
                    parent.availableFolders.remove(oldFolderName);
                    parent.availableFolders.add(newName);
                    parent.availableFolders.sort(String::compareToIgnoreCase);
                    parent.folderList.updateFolders(parent.availableFolders);

                    // If we renamed the current pattern's folder, update it
                    if (parent.pattern.getFolder().equals(oldFolderName)) {
                        parent.pattern.setFolder(newName);
                    }

                    // Mark as modified and notify parent
                    parent.foldersModified = true;
                    if (parent.parent instanceof PatternManagerScreen) {
                        ((PatternManagerScreen) parent.parent).refreshFolderList();
                    }

                    // Return to parent
                    this.minecraft.setScreen(parent);
                } else if (newName.isEmpty()) {
                    errorMessage = Component.literal("Folder name cannot be empty");
                } else if (newName.length() > 20) {
                    errorMessage = Component.literal("Maximum 20 characters allowed");
                }
            }).bounds(dialogLeft + 30, buttonY, 70, 20).build();

            Button cancelButton = Button.builder(Component.literal("Cancel"),
                            button -> this.minecraft.setScreen(parent))
                    .bounds(dialogLeft + dialogWidth - 100, buttonY, 70, 20)
                    .build();

            addRenderableWidget(renameButton);
            addRenderableWidget(cancelButton);

            // Set initial focus to the input field
            this.setInitialFocus(nameInput);

            // Set initial button state
            renameButton.active = false; // Initially disabled since name hasn't changed
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
            guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth,
                    dialogTop + dialogHeight, 0xE0000000);

            // Border
            guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + 1, 0xFFFFFFFF);
            guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + 1, dialogTop + dialogHeight, 0xFFFFFFFF);
            guiGraphics.fill(dialogLeft + dialogWidth - 1, dialogTop, dialogLeft + dialogWidth,
                    dialogTop + dialogHeight, 0xFFFFFFFF);
            guiGraphics.fill(dialogLeft, dialogTop + dialogHeight - 1, dialogLeft + dialogWidth,
                    dialogTop + dialogHeight, 0xFFFFFFFF);

            // Title
            Component title = Component.literal("Rename folder \"" + oldFolderName + "\":");
            guiGraphics.drawCenteredString(this.font, title, centerX, dialogTop + 10, 0xFFFFFF);

            // Render error message if present
            if (errorMessage != null) {
                guiGraphics.drawString(this.font, errorMessage, dialogLeft + 20, dialogTop + 75, 0xFF5555);
            }

            super.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // Handle Escape key to close dialog
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.minecraft.setScreen(parent);
                return true;
            }

            // Handle Ctrl+Z for undo
            if (keyCode == GLFW.GLFW_KEY_Z && hasControlDown() && !hasShiftDown()) {
                if (nameInputHistory != null) {
                    String previousText = nameInputHistory.undo();
                    if (previousText != null) {
                        nameInput.setValue(previousText);
                        nameInput.moveCursorToEnd();
                        // Re-validate and update button
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
                        // Re-validate and update button
                        validateAndUpdateButton(redoText);
                        return true;
                    }
                }
            }

            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                    && nameInput != null && nameInput.isFocused()) {
                String newName = nameInput.getValue().trim();
                // If there's a valid change, process it
                if (!newName.isEmpty() &&
                        !newName.equals(oldFolderName) &&
                        newName.matches(ValidationConstants.FOLDER_NAME_PATTERN) &&
                        !newName.matches("^[\\.\\s]+$") &&
                        !newName.startsWith(".") &&
                        newName.length() <= 20 &&
                        !parent.availableFolders.contains(newName)) {

                    // Valid rename - process it
                    PatternStorage.renameFolder(oldFolderName, newName);

                    parent.availableFolders.remove(oldFolderName);
                    parent.availableFolders.add(newName);
                    parent.availableFolders.sort(String::compareToIgnoreCase);
                    parent.folderList.updateFolders(parent.availableFolders);

                    if (parent.pattern.getFolder().equals(oldFolderName)) {
                        parent.pattern.setFolder(newName);
                    }

                    // Mark as modified and notify parent
                    parent.foldersModified = true;
                    if (parent.parent instanceof PatternManagerScreen) {
                        ((PatternManagerScreen) parent.parent).refreshFolderList();
                    }

                    this.minecraft.setScreen(parent);
                } else {
                    // Show appropriate error
                    if (newName.isEmpty()) {
                        errorMessage = Component.literal("Folder name cannot be empty");
                    } else if (!newName.matches(ValidationConstants.FOLDER_NAME_PATTERN)) {
                        errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
                    } else if (newName.length() > 20) {
                        errorMessage = Component.literal("Maximum 20 characters allowed");
                    } else if (parent.availableFolders.contains(newName)) {
                        errorMessage = Component.literal("Folder already exists");
                    } else {
                        // No change, just close
                        this.minecraft.setScreen(parent);
                    }
                }
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void onClose() {
            // Clean up text field history to prevent memory leak
            if (nameInputHistory != null) {
                nameInputHistory.cleanup();
            }
            super.onClose();
        }

        private void validateAndUpdateButton(String text) {
            if (text.isEmpty() || text.trim().equals(oldFolderName)) {
                errorMessage = null;
                renameButton.active = false;
            } else if (!text.matches("^[a-zA-Z0-9._\\- ]*$")) {
                errorMessage = Component.literal("Only letters, numbers, . _ - and spaces allowed");
                renameButton.active = false;
            } else if (parent.availableFolders.contains(text.trim()) && !text.trim().equals(oldFolderName)) {
                errorMessage = Component.literal("Folder already exists");
                renameButton.active = false;
            } else if (text.matches("^[\\.\\s]+$")) {
                errorMessage = Component.literal("Folder name must contain letters or numbers");
                renameButton.active = false;
            } else if (text.startsWith(".")) {
                errorMessage = Component.literal("Folder name cannot start with a dot");
                renameButton.active = false;
            } else if (text.length() > ValidationConstants.MAX_FOLDER_NAME_LENGTH) {
                errorMessage = Component.literal("Maximum " + ValidationConstants.MAX_FOLDER_NAME_LENGTH + " characters allowed");
                renameButton.active = false;
            } else {
                errorMessage = null;
                renameButton.active = true;
            }
        }
    }
}