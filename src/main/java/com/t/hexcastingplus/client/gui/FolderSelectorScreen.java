package com.t.hexcastingplus.client.gui;

import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import com.t.hexcastingplus.client.gui.TextFieldHistory;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
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

    // Drag state for folder reordering
    private boolean isDraggingFolder = false;
    private int draggedFolderIndex = -1;
    private double dragMouseY = 0;
    private int insertionIndex = -1;
    private double dragStartX = 0;
    private double dragStartY = 0;

//    @Override
//    public void renderBackground(GuiGraphics guiGraphics) {
//        // DO NOT USE, IT MAKES A DIRT BACKGROUND THAT NEEDS TO BE OVERRIDDEN WITH render()
//        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
//    }

    public FolderSelectorScreen(Screen parent, PatternStorage.SavedPattern pattern, List<String> availableFolders) {
        super(Component.literal("Move Pattern"));
        this.parent = parent;
        this.pattern = pattern; // may be null – handled in render()

        // Keep all folders including default for display
        // The list should already have default first from PatternStorage.getAvailableFolders()
        this.availableFolders = new ArrayList<>(availableFolders);

        // Ensure default is always first
        this.availableFolders.remove("default");
        this.availableFolders.add(0, "default");

        // Remove special folders (trash, casted)
        this.availableFolders.removeIf(folder -> folder.startsWith(".") && !folder.equals("default"));

        // Load folder order
        loadFolderOrder();
    }

    private void loadFolderOrder() {
        List<String> orderedFolders = PatternStorage.getFolderOrder();
        if (orderedFolders != null && !orderedFolders.isEmpty()) {
            // Create a new list based on the saved order
            List<String> newOrder = new ArrayList<>();

            // First add folders in saved order
            for (String folder : orderedFolders) {
                if (availableFolders.contains(folder)) {
                    newOrder.add(folder);
                }
            }

            // Then add any new folders that aren't in the saved order
            for (String folder : availableFolders) {
                if (!newOrder.contains(folder)) {
                    newOrder.add(folder);
                }
            }

            // Replace the list with the ordered one
            availableFolders.clear();
            availableFolders.addAll(newOrder);

            // Ensure default is always first
            availableFolders.remove("default");
            availableFolders.add(0, "default");
        }
    }

    private void saveFolderOrder() {
        // Save the current order, excluding default (which is always first)
        List<String> orderToSave = new ArrayList<>(availableFolders);
        orderToSave.remove("default");
        PatternStorage.saveFolderOrder(orderToSave);
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

        int padding = 15;
        int dialogWidth = Math.min(300, this.width - padding * 2);
        int maxDialogHeight = Math.max(180, this.height - padding * 2);
        int dialogHeight = Math.min(250, maxDialogHeight);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Dynamic list height: leave space for title (30) + input (20) + buttons (30) + error (15) + padding
        int listHeight = dialogHeight - 115;
        listHeight = Math.max(80, listHeight); // enforce minimum

        this.folderList = new FolderListWidget(this.minecraft, dialogWidth - 40, listHeight,
                dialogTop + 40, dialogTop + 40 + listHeight, 20);
        addRenderableWidget(folderList);

        // New folder input with validation
        int inputY = dialogTop + 40 + listHeight + 10;
        this.newFolderInput = new EditBox(this.font, dialogLeft + 20, inputY, dialogWidth - 120, 20,
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
        this.createFolderButton = new SmallButton(dialogLeft + dialogWidth - 90, inputY, 70, 20, Component.literal("Create"), button -> createNewFolder());
        addRenderableWidget(createFolderButton);

        // Bottom buttons
        int buttonY = dialogTop + dialogHeight - 30;
        int buttonWidth = 60;
        this.moveButton = new SmallButton(dialogLeft + 20, buttonY, buttonWidth, 20, Component.literal("Move 📁"), button -> movePattern());
        this.deleteButton = new SmallButton(dialogLeft + 30 + buttonWidth, buttonY, buttonWidth, 20, Component.literal("Delete 📁"), button -> deleteSelectedFolder());
        this.renameButton = new SmallButton(dialogLeft + 40 + buttonWidth * 2, buttonY, buttonWidth, 20, Component.literal("Rename 📁"), button -> renameSelectedFolder());

        addRenderableWidget(moveButton);
        addRenderableWidget(deleteButton);
        addRenderableWidget(renameButton);

        updateButtonStates();
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
        // Check if clicking outside of list and input - if so, defocus input and clear drag state
        boolean clickedInList = folderList != null && folderList.isMouseOver(mouseX, mouseY);
        boolean clickedInInput = newFolderInput != null && newFolderInput.isMouseOver(mouseX, mouseY);

        if (!clickedInList && !clickedInInput) {
            // Clicking outside both - defocus input if it was focused
            if (newFolderInput != null && newFolderInput.isFocused()) {
                newFolderInput.setFocused(false);
            }

            // Reset any drag preparation state
            if (button == 0) {
                isDraggingFolder = false;
                draggedFolderIndex = -1;
                insertionIndex = -1;
            }
        }

        // Handle click on input field
        if (clickedInInput) {
            // Let the input field handle it
            boolean result = super.mouseClicked(mouseX, mouseY, button);

            // Right-click to clear
            if (button == 1) {
                newFolderInputHistory.saveState(newFolderInput.getValue());
                newFolderInput.setValue("");
                errorMessage = null;
                return true;
            }

            return result;
        }

        // Handle click in list
        if (button == 0 && clickedInList) {
            int clickedIndex = folderList.getIndexAtPosition(mouseY);
            if (clickedIndex >= 0 && clickedIndex < availableFolders.size()) {
                String clickedFolder = availableFolders.get(clickedIndex);
                // Always select on click
                folderList.setSelected(clickedFolder);
                updateButtonStates();

                // Prepare for potential drag (but don't start yet)
                if (!clickedFolder.equals("default")) {
                    isDraggingFolder = false; // not yet dragging
                    draggedFolderIndex = clickedIndex;
                    dragStartX = mouseX;
                    dragStartY = mouseY;
                    dragMouseY = mouseY;
                    insertionIndex = clickedIndex;
                }
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Only process drag if we're inside the list bounds AND we have a valid drag index
        if (button == 0 && draggedFolderIndex >= 0 && folderList != null && folderList.isMouseOver(dragStartX, dragStartY)) {
            // Only start dragging after 3px movement
            if (!isDraggingFolder) {
                double dx = mouseX - dragStartX;
                double dy = mouseY - dragStartY;
                if (dx * dx + dy * dy >= 9) { // 3px threshold
                    isDraggingFolder = true;
                }
            }
            if (isDraggingFolder) {
                dragMouseY = mouseY;
                updateInsertionIndex(mouseY);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // If we were dragging, handle the drop
            if (isDraggingFolder) {
                if (draggedFolderIndex != insertionIndex && draggedFolderIndex >= 0 && insertionIndex >= 0 && insertionIndex <= availableFolders.size()) {
                    // Perform the reorder
                    String draggedFolder = availableFolders.remove(draggedFolderIndex);

                    // Adjust insertion index if needed
                    int adjustedIndex = insertionIndex;
//                    if (draggedFolderIndex < insertionIndex) {
//                        adjustedIndex--; // REMOVE THIS LINE - it's causing the misalignment
//                    }

                    // Don't allow inserting before default
                    if (adjustedIndex == 0) {
                        adjustedIndex = 1;
                    }

                    availableFolders.add(adjustedIndex, draggedFolder);

                    // Save the new order
                    saveFolderOrder();

                    // Update the list widget
                    folderList.updateFolders(availableFolders);

                    // Mark as modified
                    foldersModified = true;
                    if (parent instanceof PatternManagerScreen) {
                        ((PatternManagerScreen) parent).refreshFolderList();
                    }
                }
            }

            // Always reset drag state on mouse release
            isDraggingFolder = false;
            draggedFolderIndex = -1;
            insertionIndex = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateInsertionIndex(double mouseY) {
        if (folderList == null) return;

        int newIndex = folderList.getIndexAtPosition(mouseY - 4); // subtract 4 to fix wierd selection bug part 1
        if (newIndex >= 0 && newIndex < availableFolders.size()) {
            insertionIndex = (newIndex == 0) ? 1 : newIndex;
            return;
        }

        // Use stored y0 and itemHeight
        int listBottom = folderList.getListY0() + (availableFolders.size() * folderList.getItemHeightExposed()) - (int)folderList.getScrollAmount();

        if (mouseY >= listBottom) {
            insertionIndex = availableFolders.size();
        } else {
            insertionIndex = -1;
        }
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
        availableFolders.add(folderName); // Add to end
        folderList.updateFolders(availableFolders);
        newFolderInput.setValue("");
        errorMessage = null;

        // Save the folder order with new folder at the end
        saveFolderOrder();

        // Mark that folders were modified
        foldersModified = true;

        // Select the newly created folder
        folderList.setSelected(folderName);
        updateButtonStates();

        // Scroll to the newly created folder (at bottom)
        if (folderList != null) {
            double maxScroll = folderList.getMaxScroll();
            folderList.setScrollAmount(maxScroll);
        }

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
            // Store information before deletion (like PatternManagerScreen)
            String selectedFolderName = selectedFolder;
            int deletedIndex = availableFolders.indexOf(selectedFolderName);
            double currentScroll = folderList.getScrollAmount();
            int totalItems = folderList.children().size();
            int itemHeight = 20; // folder item height

            // Store the folder name that will be selected after deletion
            String nextSelectionName = null;

            if (!availableFolders.isEmpty()) {
                // Always try next item first, then previous
                if (deletedIndex < availableFolders.size() - 1) {
                    nextSelectionName = availableFolders.get(deletedIndex + 1);
                } else if (deletedIndex > 0) {
                    nextSelectionName = availableFolders.get(deletedIndex - 1);
                }
            }

            // Calculate viewport information
            double viewportTop = currentScroll;
            double viewportHeight = folderList.getHeight();
            double deletedItemTop = deletedIndex * itemHeight;
            double deletedItemBottom = deletedItemTop + itemHeight;

            // Delete empty folder immediately
            PatternStorage.deleteFolder(selectedFolder);
            availableFolders.remove(selectedFolder);
            folderList.updateFolders(availableFolders);

            // Calculate new scroll position
            double newScroll = currentScroll;

            // If we deleted an item above the current viewport, adjust scroll up by one item height
            if (deletedItemBottom < viewportTop) {
                newScroll = Math.max(0, currentScroll - itemHeight);
            }
            // If item was deleted within the top portion of viewport, add subtle adjustment
            else if (deletedItemTop < viewportTop + itemHeight && deletedItemTop >= viewportTop) {
                double adjustmentFactor = (viewportTop + itemHeight - deletedItemTop) / itemHeight;
                newScroll = Math.max(0, currentScroll - (itemHeight * adjustmentFactor * 0.5));
            }

            // Apply the new scroll position immediately
            if (folderList != null) {
                folderList.setScrollAmount(newScroll);
            }

            // Auto-select next folder
            if (folderList.children().size() > 0) {
                int newItemCount = folderList.children().size();
                if (newItemCount > 0) {
                    if (nextSelectionName != null) {
                        // Find the folder by name
                        for (int i = 0; i < availableFolders.size(); i++) {
                            if (availableFolders.get(i).equals(nextSelectionName)) {
                                folderList.setSelected(nextSelectionName);
                                break;
                            }
                        }
                    }
                    // If not found by name, select by position
                    else {
                        int selectIndex = Math.min(deletedIndex, newItemCount - 1);
                        if (selectIndex >= 0 && selectIndex < availableFolders.size()) {
                            folderList.setSelected(availableFolders.get(selectIndex));
                        }
                    }
                }
            }

            updateButtonStates();

            // Save updated folder order
            saveFolderOrder();

            // Mark as modified and notify parent
            foldersModified = true;
            if (parent instanceof PatternManagerScreen) {
                ((PatternManagerScreen) parent).refreshFolderList();
            }
        }
    }

    private void renameSelectedFolder() {
        String selectedFolder = folderList.getSelectedFolder();
        if (selectedFolder == null || selectedFolder.equals("default")) {
            return;
        }

        // Show rename dialog
        this.minecraft.setScreen(new RenameFolderDialog(this, selectedFolder));
    }

    private void updateButtonStates() {
        String selectedFolder = folderList.getSelectedFolder();
        boolean hasSelection = selectedFolder != null;
        boolean isNotDefault = hasSelection && !selectedFolder.equals("default");
        boolean isDifferentFromCurrent = false;

        // Only compare if pattern is not null
        if (pattern != null && hasSelection) {
            isDifferentFromCurrent = !selectedFolder.equals(pattern.getFolder());
        }

        moveButton.active = isDifferentFromCurrent;
        deleteButton.active = isNotDefault;
        renameButton.active = isNotDefault;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Call renderBackground first (which now uses our custom gradient)
        this.renderBackground(guiGraphics);

        int padding = 15;
        int dialogWidth = Math.min(300, this.width - padding * 2);
        int maxDialogHeight = Math.max(180, this.height - padding * 2);
        int dialogHeight = Math.min(250, maxDialogHeight);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int dialogLeft = centerX - dialogWidth / 2;
        int dialogTop = centerY - dialogHeight / 2;

        // Dialog background – semi-transparent dark panel (like PatternManagerScreen)
        int bgColor = 0xD00A0A0A;
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + dialogHeight, bgColor);

        // Subtle border (like PatternManagerScreen)
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + dialogWidth, dialogTop + 1, 0xFF555555);
        guiGraphics.fill(dialogLeft, dialogTop, dialogLeft + 1, dialogTop + dialogHeight, 0xFF555555);
        guiGraphics.fill(dialogLeft + dialogWidth - 1, dialogTop, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xFF555555);
        guiGraphics.fill(dialogLeft, dialogTop + dialogHeight - 1, dialogLeft + dialogWidth, dialogTop + dialogHeight, 0xFF555555);

        // Title
        Component title;
        if (pattern != null) {
            title = Component.literal("Move \"" + pattern.getName() + "\" to folder:");
        } else {
            title = Component.literal("Manage Folders");
        }
        int titleX = centerX - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title, titleX, dialogTop + 10, 0xFFFFFF);

        // Compute dynamic positions for error message
        int listHeight = dialogHeight - 115;
        listHeight = Math.max(80, listHeight);
        int inputY = dialogTop + 40 + listHeight + 10;
        int errorY = inputY + 25;

        // Render error message if present
        if (errorMessage != null) {
            guiGraphics.drawString(this.font, errorMessage, dialogLeft + 20, errorY, 0xFF5555);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

//        // Render insertion indicator (green line) - only used for debugging
//        if (isDraggingFolder && insertionIndex >= 0 && folderList != null) {
//            // Calculate visual insertion position (accounting for the dragged item's gap)
//            int visualInsertionIndex = insertionIndex;
//
//            // Add 1 to dragging offset when moving down to calculate for gap
//            if (draggedFolderIndex >= 0 && insertionIndex > draggedFolderIndex) {
//                visualInsertionIndex = insertionIndex + 1;
//            }
//
//            int indicatorY;
//            if (visualInsertionIndex >= availableFolders.size()) {
//                // Inserting at the end
//                indicatorY = folderList.getListY0() + (availableFolders.size() * folderList.getItemHeightExposed()) - (int)folderList.getScrollAmount();
//            } else {
//                // Inserting before this visual index
//                indicatorY = folderList.getListY0() + (visualInsertionIndex * folderList.getItemHeightExposed()) - (int)folderList.getScrollAmount();
//            }
//
//            // Only draw if within list bounds
//            if (indicatorY >= folderList.getListY0() && indicatorY <= folderList.getListY0() + listHeight) {
//                int listLeft = folderList.getRowLeft();
//                int listWidth = folderList.getRowWidth();
//                // Draw a bright line to show where the folder will be inserted
//                guiGraphics.fill(listLeft, indicatorY - 1, listLeft + listWidth, indicatorY + 1, 0xFF00FF00);
//            }
//        }
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
        private final SmoothScrollHelper smoothScroller = new SmoothScrollHelper();

        public int getIndexAtPosition(double mouseY) {
            mouseY -= 4.0; // subtract 4 to fix wierd selection bug part 2
            // Use proper bounds checking like PatternManagerScreen
            if (mouseY < this.y0 || mouseY >= this.y1) {
                return -1;
            }
            double scrolledY = mouseY - this.y0 + this.getScrollAmount();
            int index = (int) (scrolledY / this.itemHeight);
            if (index >= 0 && index < this.getItemCount()) {
                return index;
            }
            return -1;
        }

        public int getHeight() {
            return this.y1 - this.y0;
        }


        // === CRITICAL: Override render() to skip ALL default background (override dirt background) ===
        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // Update smooth scrolling
            double newScroll = smoothScroller.updateSmoothScroll(this.getScrollAmount(), this.getMaxScroll());
            if (Math.abs(newScroll - this.getScrollAmount()) > 0.01) {
                // Only update if there's actual movement, and use super to avoid stopping velocity
                super.setScrollAmount(newScroll);
            }

            // Fill the list viewport with dark background
            guiGraphics.fill(this.x0, this.y0, this.x0 + this.width, this.y1, 0);

            // Now render entries manually
            this.renderList(guiGraphics, mouseX, mouseY, partialTick);

            // Render scrollbar with custom styling (matching PatternListWidget)
            int maxScroll = this.getMaxScroll();
            if (maxScroll > 0) {
                int scrollbarX = this.getScrollbarPosition();
                int scrollbarWidth = 6;

                int scrollbarHeight = (int)((float)((this.y1 - this.y0) * (this.y1 - this.y0)) / (float)this.getMaxPosition());
                scrollbarHeight = net.minecraft.util.Mth.clamp(scrollbarHeight, 32, this.y1 - this.y0 - 8);
                int scrollbarY = (int)this.getScrollAmount() * (this.y1 - this.y0 - scrollbarHeight) / maxScroll + this.y0;
                if (scrollbarY < this.y0) {
                    scrollbarY = this.y0;
                }

                // Dark scrollbar background
                guiGraphics.fill(scrollbarX, this.y0, scrollbarX + scrollbarWidth, this.y1, 0x60000000);

                // Scrollbar handle
                boolean isScrolling = smoothScroller.isScrolling();
                int handleColor = isScrolling ? 0xA0A0A0A0 : 0x80808080;
                int handleHighlight = isScrolling ? 0xA0E0E0E0 : 0x80C0C0C0;

                guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, handleColor);
                guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth - 1, scrollbarY + scrollbarHeight - 1, handleHighlight);
            }
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
            if (!this.isMouseOver(mouseX, mouseY)) {
                return false;
            }
            smoothScroller.addScrollVelocity(scrollDelta, this.itemHeight);
            return true;
        }

        @Override
        public void setScrollAmount(double scroll) {
            smoothScroller.stopScroll();  // Stop any ongoing velocity
            super.setScrollAmount(scroll);
        }

        @Override
        protected void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // Render entries with proper clipping like PatternManagerScreen
            int itemCount = this.getItemCount();
            for (int i = 0; i < itemCount; ++i) {
                // Skip rendering the dragged item in its original position (leave a gap)
                if (isDraggingFolder && i == draggedFolderIndex) continue;

                int rowTop = this.getRowTop(i);
                int rowBottom = rowTop + this.itemHeight;

                // CRITICAL: Only render if visible within list bounds (same as PatternManagerScreen)
                if (rowBottom >= this.y0 && rowTop <= this.y1) {
                    FolderEntry entry = this.getEntry(i);
                    boolean isHovered = this.isMouseOver(mouseX, mouseY) &&
                            java.util.Objects.equals(this.getEntryAtPosition(mouseX, mouseY), entry);

                    // Use scissor to clip content to list bounds
                    guiGraphics.enableScissor(this.x0, this.y0, this.x0 + this.width, this.y1);
                    entry.render(guiGraphics, i, rowTop, this.getRowLeft(), this.getRowWidth(),
                            this.itemHeight, mouseX, mouseY, isHovered, 1.0f);
                    guiGraphics.disableScissor();
                }
            }
            // Render ghost
            if (isDraggingFolder && draggedFolderIndex >= 0 && draggedFolderIndex < itemCount) {
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0, 0, 100);
                FolderEntry draggedEntry = this.getEntry(draggedFolderIndex);
                int ghostY = (int)dragMouseY - 10;
                guiGraphics.fill(this.getRowLeft(), ghostY, this.getRowLeft() + this.getRowWidth(),
                        ghostY + this.itemHeight, 0x80FFFFFF);
                draggedEntry.render(guiGraphics, draggedFolderIndex, ghostY, this.getRowLeft(),
                        this.getRowWidth(), this.itemHeight, mouseX, mouseY, false, 0.7f);
                guiGraphics.pose().popPose();
            }
        }

        @Override
        public int getRowLeft() {
            return this.x0;
        }

        @Override
        public int getRowWidth() {
            return this.width - (this.getMaxScroll() > 0 ? 6 : 0);
        }

        private final int listY0;
        private final int listItemHeight;

        public FolderListWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
            super(minecraft, width, height, y0, y1, itemHeight);
            this.listY0 = y0;
            this.listItemHeight = itemHeight;
            int centerX = minecraft.getWindow().getGuiScaledWidth() / 2;
            this.setLeftPos(centerX - width / 2);
            updateFolders(availableFolders);
        }

        // Expose these for hit detection
        public int getListY0() {
            return listY0;
        }

        public int getItemHeightExposed() {
            return listItemHeight;
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

            public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean hovered, float alpha) {

                // Background with different highlights for hover vs selected
                boolean isSelected = this == FolderListWidget.this.getSelected();

                // Always use fixed highlight colors – ignore 'alpha' for background
                if (isSelected) {
                    guiGraphics.fill(x, y, x + entryWidth, y + entryHeight, 0xA0FFFFFF);
                } else if (hovered) {
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

                // Always use fully opaque white text – unless it's a ghost (but even then, keep it readable)
                int textColor = (alpha >= 1.0f) ? 0xFFFFFFFF : 0xE0E0E0; // Optional: slightly dim ghost text
                // OR just use fully opaque always:
                // int textColor = 0xFFFFFFFF;

                // Folder name
                guiGraphics.drawString(minecraft.font, displayName, x + 5, textY, textColor);

                // Current folder indicator
                if (pattern != null && folderName.equals(pattern.getFolder())) {
                    int indicatorX = x + entryWidth - currentIndicatorWidth + 5; // Position dynamically
                    int indicatorColor = 0xFF808080; // Fully opaque gray
                    guiGraphics.drawString(minecraft.font, currentIndicator, indicatorX, textY, indicatorColor);
                }

                // Render tooltip for full name if truncated and hovered
                if (isTruncated && hovered && alpha >= 1.0f) {
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
                    updateButtonStates();
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

    private static class SmallButton extends Button {
        private static final float TEXT_SCALE = 0.8f;
        private static final float EMOJI_SCALE = 1.2f; // Scale for the emoji (adjust as needed)
        private final String emoji;
        private final String text;

        public SmallButton(int x, int y, int width, int height, Component text, OnPress onPress) {
            super(x, y, width, height, text, onPress, DEFAULT_NARRATION);

            // Split the text into emoji and regular text
            String fullText = text.getString();
            if (fullText.contains("📁")) {
                this.emoji = "📁";
                this.text = fullText.replace("📁", "").trim();
            } else {
                this.emoji = null;
                this.text = fullText;
            }
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // Dark button background
            int bgColor = this.isHovered ? 0xFF2A2A2A : 0xFF1A1A1A;
            if (!this.active) bgColor = 0xFF0A0A0A;
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            // Border
            int borderColor = this.active ? (this.isHovered ? 0xFF606060 : 0xFF404040) : 0xFF202020;
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, borderColor);
            guiGraphics.fill(this.getX(), this.getY() + this.height - 1, this.getX() + this.width, this.getY() + this.height, borderColor);
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.height, borderColor);
            guiGraphics.fill(this.getX() + this.width - 1, this.getY(), this.getX() + this.width, this.getY() + this.height, borderColor);

            // Text color
            int textColor = this.active ? 0xFFFFFF : 0x808080;

            float centerX = this.getX() + this.width / 2.0f;
            float centerY = this.getY() + this.height / 2.0f;

            if (emoji != null) {
                // Calculate total width with both emoji and text scaled
                int textWidth = Minecraft.getInstance().font.width(this.text);
                int emojiWidth = Minecraft.getInstance().font.width(emoji);
                float scaledTextWidth = textWidth * TEXT_SCALE;
                float scaledEmojiWidth = emojiWidth * EMOJI_SCALE;
                float totalWidth = scaledTextWidth + scaledEmojiWidth + 2; // 2px spacing

                // Render text
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(centerX - totalWidth / 2.0f, centerY, 0);
                guiGraphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0f);
                guiGraphics.drawString(Minecraft.getInstance().font, this.text, 0, -Minecraft.getInstance().font.lineHeight / 2, textColor, false);
                guiGraphics.pose().popPose();

                // Render emoji at larger scale
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(centerX + scaledTextWidth / 2.0f + 2 / EMOJI_SCALE, centerY, 0);
                guiGraphics.pose().scale(EMOJI_SCALE, EMOJI_SCALE, 1.0f);
                guiGraphics.drawString(Minecraft.getInstance().font, emoji, 0, -Minecraft.getInstance().font.lineHeight / 2, textColor, false);
                guiGraphics.pose().popPose();
            } else {
                // Original rendering for buttons without emoji
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(centerX, centerY, 0);
                guiGraphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0f);
                int textWidth = Minecraft.getInstance().font.width(this.text);
                guiGraphics.drawString(Minecraft.getInstance().font, this.text, -textWidth / 2, -Minecraft.getInstance().font.lineHeight / 2, textColor, false);
                guiGraphics.pose().popPose();
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
                // Move folder to recycle bin (handles fallback internally)
                Path folderPath = HexCastingPlusClientConfig.getPatternsDirectory().resolve(folderName);

                boolean movedToRecycleBin = HexCastingPlusClientConfig.moveToRecycleBin(folderPath);

                if (movedToRecycleBin) {
                    // Successfully moved - update UI
                    if (parent instanceof FolderSelectorScreen) {
                        FolderSelectorScreen folderScreen = (FolderSelectorScreen) parent;
                        folderScreen.availableFolders.remove(folderName);
                        folderScreen.folderList.updateFolders(folderScreen.availableFolders);
                        folderScreen.updateButtonStates();
                        folderScreen.foldersModified = true;

                        if (folderScreen.parent instanceof PatternManagerScreen) {
                            ((PatternManagerScreen) folderScreen.parent).refreshFolderList();
                        }
                    }
                } else {
                    // Complete failure - last resort: move patterns to trash and delete folder
                    List<PatternStorage.SavedPattern> patterns = PatternStorage.getSavedPatterns(folderName);
                    for (PatternStorage.SavedPattern pattern : patterns) {
                        PatternStorage.movePattern(pattern, HexCastingPlusClientConfig.TRASH_FOLDER, null);
                    }

                    PatternStorage.deleteFolder(folderName);

                    if (parent instanceof FolderSelectorScreen) {
                        FolderSelectorScreen folderScreen = (FolderSelectorScreen) parent;
                        folderScreen.availableFolders.remove(folderName);
                        folderScreen.folderList.updateFolders(folderScreen.availableFolders);
                        folderScreen.updateButtonStates();
                        folderScreen.foldersModified = true;

                        if (folderScreen.parent instanceof PatternManagerScreen) {
                            ((PatternManagerScreen) folderScreen.parent).refreshFolderList();
                        }
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
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // Handle Escape key to return to parent
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.minecraft.setScreen(parent);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
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
            Component line3 = Component.literal("Folder will be moved to recycle bin.");

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

                    // If we renamed the current pattern's folder, update it (if a pattern exists)
                    if (parent.pattern != null && parent.pattern.getFolder().equals(oldFolderName)) {
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