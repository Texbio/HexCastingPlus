package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import com.t.hexcastingplus.common.pattern.PatternCache;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

public class PatternManagerScreen extends Screen {
    private PatternListWidget patternList;
    private EditBox searchBox;
    private Button saveButton;
    private Button loadButton;
    private Button deleteButton;

    // Folder navigation
    private String currentFolder = "default";
    private Button folderLeftButton;
    private Button folderRightButton;
    private Component folderLabel;

    // Category management
    private PatternCategory currentCategory = PatternCategory.PATTERNS;
    private Button patternsButton;
    private Button trashButton;
    private Button castedButton;

    // UI Constants
    private static final int PATTERN_ITEM_HEIGHT = 35;
    private static final int UI_PADDING = 15;
    private static final int CATEGORY_BUTTON_SIZE = 20;
    private static final int CATEGORY_BUTTON_SPACING = 25;
    private static final int BOTTOM_BUTTON_WIDTH = 60;
    private static final int BOTTOM_BUTTON_HEIGHT = 20;
    private static final int BOTTOM_BUTTON_SPACING = 10;
    private static final int MAX_SEARCH_WIDTH = 200;
    private static final int SCROLL_ZONE_SIZE = 40;
    private static final double SCROLL_SPEED = 3.0;

    // Animation/Timing Constants
    private static final long CONTEXT_MENU_AUTO_HIDE_MS = 4000;
    private static final int CONTEXT_MENU_PADDING = 5;
    private static final int CONTEXT_MENU_ITEM_HEIGHT = 15;

    // Context menu
    private ContextMenu contextMenu;
    private int selectedPatternIndex = -1;

    // Pattern order management
    private Map<String, List<String>> patternOrder = new HashMap<>();

    // Crosshair state tracking
    private boolean originalCrosshairState = false;
    private boolean originalHideGui = false;    // hiding minecraft crosshair

    // Search box
    private String lastSearchTerm = "";
    private boolean searchBoxWasFocused = false;

    // Search throttling
    private String pendingSearchTerm = "";

    //temp
    private final GuiSpellcasting staffGui;
    private boolean hasStoredOriginalState = false;
    private DragDropManager dragDropManager;
    private int folderNavigationY = 40;

    private final List<String> availableFolders;
    private final Set<String> favoritePatterns = new HashSet<>();

    // Static search history that persists across GUI opens/closes
    private static final List<String> searchHistory = new ArrayList<>();
    private static int historyIndex = -1;
    private static final int MAX_HISTORY = 20;

    // temp
    private static double TEXT_SCALE = 0.8;

    // Empty Trash button variables
    private Button emptyTrashButton;
    private long emptyTrashHoldStartTime = -1;
    private boolean isHoldingEmptyTrash = false;
    private static final long EMPTY_TRASH_HOLD_DURATION = 2000; // 3 seconds
    private long emptyTrashReleaseTime = -1; // For reverse animation
    private float emptyTrashMaxProgress = 0f; // Track max progress reached
    private float emptyTrashCurrentProgress = 0f; // So we don't need to recalculate
    private static final int EMPTY_TRASH_EXTRA_WIDTH = 10;

    // =========== scroll position
    private double preservedScrollPosition = 0;
    private boolean returningFromDialog = false;

    public void updatePatternList() {
        // Preserve scroll position
        double previousScroll = patternList != null ? patternList.getScrollAmount() : 0;
        int previousItemCount = patternList != null ? patternList.children().size() : 0;

        List<PatternStorage.SavedPattern> patterns;
        String searchTerm = searchBox != null ? searchBox.getValue().toLowerCase() : "";

        switch (currentCategory) {
            case TRASH:
                patterns = PatternStorage.getTrashedPatterns();
                patterns = sortPatternsByOrder(patterns);
                break;
            case CASTED:
                patterns = PatternStorage.getCastedPatterns();
                patterns = sortPatternsByOrder(patterns);
                break;
            default:
                patterns = PatternStorage.getSavedPatterns(currentFolder);
                patterns = sortPatternsByOrder(patterns);
                break;
        }

        // Apply search filter
        if (!searchTerm.isEmpty()) {
            patterns = patterns.stream()
                    .filter(p -> p.getName().toLowerCase().contains(searchTerm))
                    .collect(Collectors.toList());
        }

        patternList.updatePatterns(patterns);

        // Smart scroll restoration
        if (patternList != null && previousItemCount > 0) {
            int newItemCount = patternList.children().size();

            if (newItemCount > 0) {
                double maxScroll = patternList.getMaxScroll();

                if (newItemCount != previousItemCount) {
                    if (newItemCount < previousItemCount && previousScroll > 0) {
                        double scrollPercentage = previousScroll / (previousItemCount * (double)PATTERN_ITEM_HEIGHT);
                        double newScroll = scrollPercentage * (newItemCount * (double)PATTERN_ITEM_HEIGHT);
                        patternList.setScrollAmount(Math.min(newScroll, maxScroll));
                    } else {
                        patternList.setScrollAmount(Math.min(previousScroll, maxScroll));
                    }
                } else {
                    patternList.setScrollAmount(previousScroll);
                }
            }
        }
    }

    public void updatePatternOrderCache(String folder, String oldName, String newName) {
        List<String> order = patternOrder.getOrDefault(folder, new ArrayList<>());

        int index = order.indexOf(oldName);
        if (index >= 0) {
            order.set(index, newName);
        } else {
            order.add(newName);
        }

        patternOrder.put(folder, order);

        // Don't call updatePatternList() here - let the caller handle it
        // REMOVED: updatePatternList();
    }

    private class ContextMenu {
        private boolean visible = false;
        private int x, y;
        private PatternStorage.SavedPattern targetPattern;
        private long showTime;
        // Use the constant instead of hardcoded value
        private final long AUTO_HIDE_TIME = CONTEXT_MENU_AUTO_HIDE_MS;

        // Menu items
        // Menu items
        private final List<MenuItem> menuItems = Arrays.asList(
                new MenuItem("Rename", this::openRenameDialog),
                new MenuItem("Edit", this::openEditDialog),
                new MenuItem("Duplicate", this::duplicatePattern),
                new MenuItem("Move", this::openMoveDialog),
                new MenuItem("Delete", this::deleteSelectedPattern)
        );

        // Dynamic sizing - use constants
        private int menuWidth;
        private int menuHeight;
        private final int PADDING = CONTEXT_MENU_PADDING;
        private final int ITEM_HEIGHT = CONTEXT_MENU_ITEM_HEIGHT;

        public void show(int mouseX, int mouseY, PatternStorage.SavedPattern pattern) {
            this.visible = true;
            this.targetPattern = pattern;
            this.showTime = System.currentTimeMillis();

            // Calculate dynamic menu size based on text
            calculateMenuSize();

            // Position menu, ensuring it stays on screen
            this.x = mouseX;
            this.y = mouseY;

            // Adjust if menu would go off screen
            if (x + menuWidth > PatternManagerScreen.this.width) {
                x = PatternManagerScreen.this.width - menuWidth - 5;
            }
            if (y + menuHeight > PatternManagerScreen.this.height) {
                y = PatternManagerScreen.this.height - menuHeight - 5;
            }
        }

        private void calculateMenuSize() {
            // Find the widest menu item
            int maxWidth = 0;
            for (MenuItem item : menuItems) {
                int textWidth = font.width(item.label);
                maxWidth = Math.max(maxWidth, textWidth);
            }

            // Add padding
            menuWidth = maxWidth + (PADDING * 2);

            // Calculate height based on number of items
            menuHeight = (menuItems.size() * ITEM_HEIGHT) + PADDING;
        }

        public void hide() {
            this.visible = false;
        }

        public boolean isVisible() {
            return visible;
        }

        public void tick() {
            if (visible && System.currentTimeMillis() - showTime > AUTO_HIDE_TIME) {
                hide();
            }
        }

        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
            if (!visible) return;

            // Check if mouse is hovering over menu
            boolean mouseOver = mouseX >= x && mouseX <= x + menuWidth &&
                    mouseY >= y && mouseY <= y + menuHeight;
            if (mouseOver) {
                showTime = System.currentTimeMillis(); // Reset auto-hide timer
            }

            // Render menu background with border
            guiGraphics.fill(x, y, x + menuWidth, y + menuHeight, 0xE0000000);

            // Render border (subtle)
            guiGraphics.fill(x, y, x + menuWidth, y + 1, 0xFF555555); // Top
            guiGraphics.fill(x, y + menuHeight - 1, x + menuWidth, y + menuHeight, 0xFF555555); // Bottom
            guiGraphics.fill(x, y, x + 1, y + menuHeight, 0xFF555555); // Left
            guiGraphics.fill(x + menuWidth - 1, y, x + menuWidth, y + menuHeight, 0xFF555555); // Right

            // Render menu items with hover effect
            int currentY = y + PADDING;
            for (int i = 0; i < menuItems.size(); i++) {
                MenuItem item = menuItems.get(i);

                // Check if mouse is over this item
                boolean itemHovered = mouseOver &&
                        mouseY >= currentY &&
                        mouseY < currentY + ITEM_HEIGHT;

                // Render hover background
                if (itemHovered) {
                    guiGraphics.fill(x + 1, currentY - 2, x + menuWidth - 1,
                            currentY + ITEM_HEIGHT - 2, 0x40FFFFFF);
                }

                // Render text
                int textColor = itemHovered ? 0xFFFFFF : 0xCCCCCC;
                guiGraphics.drawString(font, item.label, x + PADDING, currentY, textColor);

                currentY += ITEM_HEIGHT;
            }
        }

        public boolean handleClick(double mouseX, double mouseY) {
            if (!visible) return false;

            if (mouseX >= x && mouseX <= x + menuWidth && mouseY >= y && mouseY <= y + menuHeight) {
                // Find which item was clicked
                int clickedIndex = (int)((mouseY - y - PADDING) / ITEM_HEIGHT);

                if (clickedIndex >= 0 && clickedIndex < menuItems.size()) {
                    menuItems.get(clickedIndex).action.run();
                }

                hide();
                return true;
            }
            return false;
        }

        private void duplicatePattern() {
            if (targetPattern != null) {
                String originalName = targetPattern.getName();
                String folder = targetPattern.getFolder();

                // Create a new name for the duplicate
                String baseName = originalName;
                // Remove any existing number suffix like "_2", "_3" etc.
                if (baseName.matches(".*_\\d+$")) {
                    baseName = baseName.substring(0, baseName.lastIndexOf('_'));
                }

                // Find a unique name for the duplicate
                String newName = PatternStorage.ensureUniqueFileName(baseName + "_copy", folder);

                // Copy the actual file instead of reconstructing
                boolean success = PatternStorage.copyPatternFile(originalName, newName, folder);

                if (success) {
                    // Update pattern order - insert right after the original
                    Map<String, List<String>> patternOrder = PatternStorage.loadPatternOrder();
                    List<String> order = new ArrayList<>(patternOrder.getOrDefault(folder, new ArrayList<>()));

                    // Remove the new name if it somehow already exists in the order
                    order.remove(newName);

                    // Find the original's position
                    int originalIndex = order.indexOf(originalName);
                    if (originalIndex >= 0) {
                        // Insert right after the original (this shifts everything else down)
                        order.add(originalIndex + 1, newName);
                    } else {
                        // If not found, add based on folder type
                        if (folder.equals(PatternStorage.TRASH_FOLDER) || folder.equals(PatternStorage.CASTED_FOLDER)) {
                            // Special folders: add at top after favorites
                            int insertPos = 0;
                            List<PatternStorage.SavedPattern> allPatterns = patternList.getAllPatterns();
                            for (PatternStorage.SavedPattern p : allPatterns) {
                                if (p.isFavorite()) {
                                    insertPos++;
                                }
                            }
                            order.add(insertPos, newName);
                        } else {
                            // Normal folders: add at bottom
                            order.add(newName);
                        }
                    }

                    // Save the updated order
                    patternOrder.put(folder, order);
                    PatternStorage.savePatternOrder(patternOrder);

                    // Update our local cache too
                    PatternManagerScreen.this.patternOrder = patternOrder;

                    // Store scroll before update
                    double scrollBefore = PatternManagerScreen.this.patternList != null ?
                            PatternManagerScreen.this.patternList.getScrollAmount() : 0;

                    // Update the pattern list
                    PatternManagerScreen.this.updatePatternList();

                    // Restore scroll immediately
                    if (PatternManagerScreen.this.patternList != null) {
                        PatternManagerScreen.this.patternList.setScrollAmount(scrollBefore);
                    }

                    // Find and select the duplicated pattern
                    List<PatternStorage.SavedPattern> patternsList = patternList.getAllPatterns();
                    for (int i = 0; i < patternsList.size(); i++) {
                        if (patternsList.get(i).getName().equals(newName)) {
                            PatternManagerScreen.this.selectedPatternIndex = i;
                            PatternManagerScreen.this.patternList.setSelected(
                                    PatternManagerScreen.this.patternList.children().get(i)
                            );
                            PatternManagerScreen.this.updateButtonStates();
                            break;
                        }
                    }
                }
            }
        }

        private void openRenameDialog() {
            if (targetPattern != null) {
                preserveScroll();
                returningFromDialog = true;
                minecraft.setScreen(new RenamePatternDialog(PatternManagerScreen.this, targetPattern));
            }
        }

        private void openMoveDialog() {
            if (targetPattern != null) {
                preserveScroll();
                returningFromDialog = true;
                minecraft.setScreen(new FolderSelectorScreen(PatternManagerScreen.this, targetPattern, availableFolders));
            }
        }

        private void openEditDialog() {
            if (targetPattern != null) {
                preserveScroll();
                returningFromDialog = true;
                String currentLocation = PatternManagerScreen.this.getActualCurrentLocation();
                minecraft.setScreen(new PatternEditScreen(PatternManagerScreen.this, targetPattern, currentLocation));
            }
        }

        private void deleteSelectedPattern() {
            PatternManagerScreen.this.deleteSelectedPattern();
        }

        // Helper class for menu items
        private static class MenuItem {
            final String label;
            final Runnable action;

            MenuItem(String label, Runnable action) {
                this.label = label;
                this.action = action;
            }
        }
    }

    private void openSaveDialog() {
        if (hasPatternsTosave()) {
            preserveScroll();
            returningFromDialog = true;
            this.minecraft.setScreen(new SavePatternDialog(this, currentFolder, staffGui));
        }
    }

    private void preserveScroll() {
        if (patternList != null) {
            preservedScrollPosition = patternList.getScrollAmount();
        }
    }

    private void restoreScroll() {
        if (patternList != null && preservedScrollPosition >= 0) {
            patternList.setScrollAmount(preservedScrollPosition);
            preservedScrollPosition = 0; // Reset after use
        }
    }

    // ========== Empty trash animation

    private class EmptyTrashButton extends SmallButton {
        public EmptyTrashButton(int x, int y, int width, int height, Component text, OnPress onPress) {
            super(x, y, width, height, text, onPress);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

            float progress = 0f;

            if (isHoldingEmptyTrash && emptyTrashHoldStartTime > 0) {
                // Forward animation
                long holdDuration = System.currentTimeMillis() - emptyTrashHoldStartTime;
                progress = Math.min(1.0f, holdDuration / (float)EMPTY_TRASH_HOLD_DURATION);
                emptyTrashMaxProgress = progress;
                emptyTrashCurrentProgress = progress; // Store current progress

            } else if (emptyTrashReleaseTime > 0 && emptyTrashMaxProgress > 0) {
                // Reverse animation at 2x speed
                long releaseDuration = System.currentTimeMillis() - emptyTrashReleaseTime;
                float decreaseAmount = (releaseDuration * 2) / (float)EMPTY_TRASH_HOLD_DURATION;
                progress = Math.max(0, emptyTrashMaxProgress - decreaseAmount);
                emptyTrashCurrentProgress = progress; // Store current progress

                if (progress <= 0) {
                    emptyTrashReleaseTime = -1;
                    emptyTrashMaxProgress = 0f;
                    emptyTrashCurrentProgress = 0f;
                }
            }

            if (progress > 0) {
                int fillWidth = (int)(this.width * progress);
                guiGraphics.fill(this.getX(), this.getY(),
                        this.getX() + fillWidth, this.getY() + this.height,
                        0x60FF4444);
            }
        }
    }

    // ==========

    public DragDropManager getDragDropManager() {
        return dragDropManager;
    }

    public enum PatternCategory {
        PATTERNS("Patterns"),
        TRASH("Trash"),
        CASTED("Casted");

        private final String displayName;

        PatternCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public PatternManagerScreen(GuiSpellcasting staffGui) {
        super(Component.literal("Pattern Manager"));
        this.staffGui = staffGui;
        this.availableFolders = PatternStorage.getAvailableFolders();
        if (!availableFolders.contains("default")) {
            availableFolders.add(0, "default");
        }

        this.currentFolder = PatternStorage.getLastUsedFolder();
        if (!availableFolders.contains(currentFolder)) {
            this.currentFolder = "default";
        }

        patternOrder = PatternStorage.loadPatternOrder();

        // Initialize drag drop manager
        this.dragDropManager = new DragDropManager(this);
        // Don't initialize history here - let the search box handle it
    }

    @Override
    public void onClose() {
        // Restore original hideGui state
        if (this.minecraft != null && this.minecraft.options != null) {
            this.minecraft.options.hideGui = this.originalHideGui;
        }

        super.onClose();
        PatternListWidget.PatternEntry.clearAnimationState();
    }

    @Override
    public void removed() {
        // Also restore in removed() as a failsafe
        if (this.minecraft != null && this.minecraft.options != null) {
            this.minecraft.options.hideGui = this.originalHideGui;
        }
        super.removed();
    }

    @Override
    protected void init() {
        super.init();

        // Use full screen dimensions with padding
        int padding = UI_PADDING;
        int guiLeft = padding;
        int guiTop = padding;
        int guiWidth = this.width - (padding * 2);
        int guiHeight = this.height - (padding * 2);

        // Category buttons (P, T, C) - smaller like in staff GUI
        int categoryX = guiLeft + 5;
        int categoryY = guiTop + 20;
        int categoryButtonSize = CATEGORY_BUTTON_SIZE;
        int categorySpacing = CATEGORY_BUTTON_SPACING;

        this.patternsButton = new SmallButton(categoryX, categoryY, categoryButtonSize, categoryButtonSize,
                Component.literal("P"), button -> setCategory(PatternCategory.PATTERNS));

        this.trashButton = new SmallButton(categoryX, categoryY + categorySpacing, categoryButtonSize, categoryButtonSize,
                Component.literal("T"), button -> setCategory(PatternCategory.TRASH));

        this.castedButton = new SmallButton(categoryX, categoryY + categorySpacing * 2, categoryButtonSize, categoryButtonSize,
                Component.literal("C"), button -> setCategory(PatternCategory.CASTED));

        addRenderableWidget(patternsButton);
        addRenderableWidget(trashButton);
        addRenderableWidget(castedButton);

        // Store original state and hide GUI elements (including crosshair)
        if (this.minecraft != null && this.minecraft.options != null) {
            if (!hasStoredOriginalState) {
                // First time opening PatternManagerScreen - store the original state
                this.originalHideGui = this.minecraft.options.hideGui;
                hasStoredOriginalState = true;
            }
            // Always hide GUI when this screen is active
            this.minecraft.options.hideGui = true;
        }

        // Folder navigation - centered at top
        int folderY = guiTop + 20;
        updateFolderNavigation(this.width / 2, folderY);

        // Pattern list
        int listX = guiLeft + 35;
        int listY = guiTop + 50;
        int listWidth = guiWidth - 50;
        int listHeight = guiHeight - 100;

        this.patternList = new PatternListWidget(this.minecraft, listWidth, listHeight,
                listY, listY + listHeight, PATTERN_ITEM_HEIGHT, this);
        this.patternList.setLeftPos(listX);
        addRenderableWidget(patternList);

        // Calculate folder navigation bounds to prevent overlap
        int folderNavEndX = Integer.MAX_VALUE; // Default if no folder nav
        if (folderRightButton != null && folderRightButton.visible) {
            folderNavEndX = folderRightButton.getX() + folderRightButton.getWidth() + 10; // 10px buffer
        }

        // Search box - aligned with RIGHT edge of pattern list
        int maxSearchWidth = MAX_SEARCH_WIDTH;
        int minSearchWidth = 100; // Minimum width before it looks too small
        int searchWidth = Math.min(maxSearchWidth, this.width / 4);

        // Calculate right-aligned position
        int listRightEdge = listX + listWidth;
        int searchX = listRightEdge - searchWidth;

        // Check for overlap with folder navigation and scale down if needed
        if (searchX < folderNavEndX) {
            // Calculate available space between folder nav and list right edge
            int availableSpace = listRightEdge - folderNavEndX;
            searchWidth = Math.max(minSearchWidth, availableSpace - 5); // 5px padding
            searchX = listRightEdge - searchWidth;
        }

        this.searchBox = new PatternSearchBox(this.font, searchX, folderY, searchWidth, 20, Component.literal("Search"), this);
        this.searchBox.setHint(Component.literal("Search 🔍"));
        this.searchBox.setMaxLength(50);
        addRenderableWidget(searchBox);

        // Bottom buttons - smaller and centered (no back button)
        int buttonY = guiTop + guiHeight - 30;
        int buttonWidth = BOTTOM_BUTTON_WIDTH;
        int buttonHeight = BOTTOM_BUTTON_HEIGHT;
        int buttonSpacing = BOTTOM_BUTTON_SPACING;

        // Calculate button positions based on whether we're in trash category
        // This is initially for non-trash, but we'll adjust in updateButtonStates
        int totalButtonWidth = (buttonWidth * 3) + (buttonSpacing * 2);
        int buttonStartX = (this.width - totalButtonWidth) / 2;

        this.saveButton = new SmallButton(buttonStartX, buttonY, buttonWidth, buttonHeight,
                Component.literal("Save"), button -> openSaveDialog());

        this.loadButton = new SmallButton(buttonStartX + buttonWidth + buttonSpacing, buttonY, buttonWidth, buttonHeight,
                Component.literal("Load"), button -> loadSelectedPattern());

        this.deleteButton = new SmallButton(buttonStartX + (buttonWidth + buttonSpacing) * 2, buttonY, buttonWidth, buttonHeight,
                Component.literal("Delete"), button -> deleteSelectedPattern());

        // Empty Trash button - position will be adjusted when visible
        this.emptyTrashButton = new EmptyTrashButton(
                buttonStartX + (buttonWidth + buttonSpacing) * 3, buttonY,
                buttonWidth + EMPTY_TRASH_EXTRA_WIDTH, buttonHeight,
                Component.literal("Empty Trash"),
                button -> {});
        emptyTrashButton.visible = false;
        addRenderableWidget(emptyTrashButton);

        addRenderableWidget(saveButton);
        addRenderableWidget(loadButton);
        addRenderableWidget(deleteButton);

        // Context menu
        this.contextMenu = new ContextMenu();

        updatePatternList();
        updateButtonStates();
    }

    public static int getPatternItemHeight() {
        return PATTERN_ITEM_HEIGHT;
    }

    public boolean isDraggedFromHere(int index) {
        if (dragDropManager != null && dragDropManager.isDragging()) {
            String currentLocation = getActualCurrentLocation();
            return dragDropManager.isSourceIndex(index, currentLocation);
        }
        return false;
    }

    // Custom small button class
    private static class SmallButton extends Button {
        private static final float TEXT_SCALE = 0.8f;

        public SmallButton(int x, int y, int width, int height, Component text, OnPress onPress) {
            super(x, y, width, height, text, onPress, DEFAULT_NARRATION);
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

            // Text - smaller font without drop shadow
            int textColor = this.active ? 0xFFFFFF : 0x808080;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(this.getX() + this.width / 2.0f,
                    this.getY() + this.height / 2.0f, 0);
            guiGraphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0f);

            // Calculate text width for manual centering
            int textWidth = Minecraft.getInstance().font.width(this.getMessage());

            // Draw without shadow (false parameter)
            guiGraphics.drawString(Minecraft.getInstance().font, this.getMessage(),
                    -textWidth / 2, -Minecraft.getInstance().font.lineHeight / 2,
                    textColor, false);
            guiGraphics.pose().popPose();
        }
    }

    private void updateFolderNavigation(int centerX, int y) {
        // Store the Y position for later use when refreshing
        this.folderNavigationY = y;

        // Remove existing folder buttons
        if (folderLeftButton != null) {
            removeWidget(folderLeftButton);
        }
        if (folderRightButton != null) {
            removeWidget(folderRightButton);
        }

        // Create folder label with truncation
        String displayFolderName = truncateFolderName(currentFolder);
        folderLabel = Component.literal(displayFolderName);

        // Get navigable folders
        List<String> navigableFolders = availableFolders.stream()
                .filter(f -> !f.startsWith("."))
                .collect(Collectors.toList());

        // Ensure default is first
        navigableFolders.remove("default");
        navigableFolders.add(0, "default");

        int currentIndex = navigableFolders.indexOf(currentFolder);

        // Calculate fixed width based on actual longest folder name (truncated if needed)
        int maxFolderWidth = getMaxFolderNameWidth();

        // Fixed layout
        int buttonWidth = 20;
        int spacing = 8;
        int totalWidth = buttonWidth + spacing + maxFolderWidth + spacing + buttonWidth;
        int startX = centerX - totalWidth / 2;

        // Create buttons with fixed positions
        this.folderLeftButton = new SmallButton(startX, y, buttonWidth, 20,
                Component.literal("<"), button -> {
            List<String> folders = availableFolders.stream()
                    .filter(f -> !f.startsWith("."))
                    .collect(Collectors.toList());
            folders.remove("default");
            folders.add(0, "default");
            int idx = folders.indexOf(currentFolder);

            // Check if shift is held
            if (Screen.hasShiftDown()) {
                // Jump to first folder
                currentFolder = folders.get(0);
            } else if (idx > 0) {
                // Normal behavior - previous folder
                currentFolder = folders.get(idx - 1);
            } else {
                return; // No change needed
            }

            PatternStorage.setLastUsedFolder(currentFolder);
            updateFolderNavigation(this.width / 2, y);
            updatePatternList();
        });

        this.folderRightButton = new SmallButton(startX + buttonWidth + spacing + maxFolderWidth + spacing, y, buttonWidth, 20,
                Component.literal(">"), button -> {
            List<String> folders = availableFolders.stream()
                    .filter(f -> !f.startsWith("."))
                    .collect(Collectors.toList());
            folders.remove("default");
            folders.add(0, "default");
            int idx = folders.indexOf(currentFolder);

            // Check if shift is held
            if (Screen.hasShiftDown()) {
                // Jump to last folder
                currentFolder = folders.get(folders.size() - 1);
            } else if (idx < folders.size() - 1) {
                // Normal behavior - next folder
                currentFolder = folders.get(idx + 1);
            } else {
                return; // No change needed
            }

            PatternStorage.setLastUsedFolder(currentFolder);
            updateFolderNavigation(this.width / 2, y);
            updatePatternList();
        });

        addRenderableWidget(folderLeftButton);
        addRenderableWidget(folderRightButton);

        // Update button states
        folderLeftButton.active = currentIndex > 0;
        folderRightButton.active = currentIndex < navigableFolders.size() - 1;
    }

    private int getMaxFolderNameWidth() {
        int maxWidth = 0;
        for (String folderName : availableFolders) {
            if (!folderName.startsWith(".")) {
                // Truncate if needed before measuring
                String displayName = truncateFolderName(folderName);
                int width = font.width(Component.literal(displayName));
                maxWidth = Math.max(maxWidth, width);
            }
        }

        // Still cap at reasonable max (in case all folders are long)
        String maxChars = "M".repeat(ValidationConstants.MAX_FOLDER_NAME_LENGTH);
        int maxAllowedWidth = font.width(Component.literal(maxChars));
        maxWidth = Math.min(maxWidth, maxAllowedWidth);

        return maxWidth + 2; // Add 1px padding on each side
    }

    private String truncateFolderName(String folderName) {
        if (folderName.length() <= ValidationConstants.MAX_FOLDER_NAME_LENGTH) {
            return folderName;
        }
        // Replace last 3 characters with "..." for folders longer than max
        return folderName.substring(0, ValidationConstants.MAX_FOLDER_NAME_LENGTH - 3) + "...";
    }

    /**
     * Refreshes the folder list from disk and updates the navigation
     * Called by FolderSelectorScreen when folders are created/deleted/renamed
     */
    public void refreshFolderList() {
        // Reload the folders list from disk
        List<String> newFolders = PatternStorage.getAvailableFolders();

        // Clear and update our cached list
        this.availableFolders.clear();
        this.availableFolders.addAll(newFolders);

        // Ensure default is first
        if (!availableFolders.contains("default")) {
            availableFolders.add(0, "default");
        }

        // Check if current folder still exists
        if (!availableFolders.contains(currentFolder)) {
            currentFolder = "default";
            PatternStorage.setLastUsedFolder(currentFolder);
            // Need to refresh pattern list if folder changed
            updatePatternList();
        }

        // Update the folder navigation buttons with consistent positioning
        // The buttons will now stay in the same position regardless of folder name length
        updateFolderNavigation(this.width / 2, folderNavigationY);
    }

    private void setCategory(PatternCategory category) {
        boolean categoryChanged = this.currentCategory != category;
        this.currentCategory = category;

        if (categoryChanged) {
            emptyTrashHoldStartTime = -1;
            isHoldingEmptyTrash = false;
            emptyTrashReleaseTime = -1;
            emptyTrashMaxProgress = 0f;
            emptyTrashCurrentProgress = 0f;
        }

        if (searchBox != null) {
            searchBox.setValue("");
            lastSearchTerm = "";
        }

        updatePatternList();

        if (categoryChanged && patternList != null) {
            patternList.setScrollAmount(0);
        }

        updateButtonStates();
    }

    private void updateButtonStates() {
        patternsButton.active = currentCategory != PatternCategory.PATTERNS;
        trashButton.active = currentCategory != PatternCategory.TRASH;
        castedButton.active = currentCategory != PatternCategory.CASTED;

        boolean hasSelection = selectedPatternIndex >= 0;
        loadButton.active = hasSelection;
        deleteButton.active = hasSelection;
        saveButton.active = currentCategory == PatternCategory.PATTERNS && hasPatternsTosave();

        if (folderLeftButton != null) {
            folderLeftButton.visible = currentCategory == PatternCategory.PATTERNS;
            folderRightButton.visible = currentCategory == PatternCategory.PATTERNS;
        }

        // Reposition buttons based on whether Empty Trash is visible
        if (currentCategory == PatternCategory.TRASH) {
            int buttonWidth = BOTTOM_BUTTON_WIDTH;
            int buttonSpacing = BOTTOM_BUTTON_SPACING;
            int emptyTrashWidth = buttonWidth + EMPTY_TRASH_EXTRA_WIDTH;
            int totalWidth = (buttonWidth * 3) + emptyTrashWidth + (buttonSpacing * 3);
            int startX = (this.width - totalWidth) / 2;

            saveButton.setX(startX);
            loadButton.setX(startX + buttonWidth + buttonSpacing);
            deleteButton.setX(startX + (buttonWidth + buttonSpacing) * 2);
            emptyTrashButton.setX(startX + (buttonWidth + buttonSpacing) * 3);

            emptyTrashButton.visible = true;
            List<PatternStorage.SavedPattern> trashPatterns = PatternStorage.getTrashedPatterns();
            emptyTrashButton.active = !trashPatterns.isEmpty();
        } else {
            // Hide Empty Trash and center the other three buttons
            int buttonWidth = BOTTOM_BUTTON_WIDTH;
            int buttonSpacing = BOTTOM_BUTTON_SPACING;
            int totalWidth = (buttonWidth * 3) + (buttonSpacing * 2);
            int startX = (this.width - totalWidth) / 2;

            saveButton.setX(startX);
            loadButton.setX(startX + buttonWidth + buttonSpacing);
            deleteButton.setX(startX + (buttonWidth + buttonSpacing) * 2);

            emptyTrashButton.visible = false;
        }
    }

    public String getActualCurrentLocation() {
        switch (currentCategory) {
            case TRASH:
                return PatternStorage.TRASH_FOLDER;
            case CASTED:
                return PatternStorage.CASTED_FOLDER;
            case PATTERNS:
            default:
                return currentFolder;
        }
    }

    private boolean hasPatternsTosave() {
        // Check if we have any tracked patterns (drawn or loaded)
        List<HexPattern> cachedPatterns = PatternCache.getMergedPatterns();
        return !cachedPatterns.isEmpty();
    }

    private void restoreGuiState() {
        if (this.minecraft != null && this.minecraft.options != null) {
            this.minecraft.options.hideGui = this.originalHideGui;
        }
    }

    private List<PatternStorage.SavedPattern> sortPatternsByOrder(List<PatternStorage.SavedPattern> patterns) {
        String actualLocation = getActualCurrentLocation();

        // Always reload the pattern order from disk to get the latest
        patternOrder = PatternStorage.loadPatternOrder();
        List<String> order = patternOrder.getOrDefault(actualLocation, new ArrayList<>());

        // Add missing patterns (for manually added files, etc.)
        // BUT not during drag operations to avoid interference
        boolean orderChanged = false;
        Set<String> orderSet = new HashSet<>(order);
        for (PatternStorage.SavedPattern pattern : patterns) {
            if (!orderSet.contains(pattern.getName())) {
                // Don't add during active drag operations
                if (dragDropManager == null || !dragDropManager.isDragging()) {
                    order.add(pattern.getName());
                    orderChanged = true;
                }
            }
        }

        if (orderChanged) {
            patternOrder.put(actualLocation, order);
            PatternStorage.savePatternOrder(patternOrder);
        }

        // Separate favorites and non-favorites
        List<PatternStorage.SavedPattern> favorites = new ArrayList<>();
        List<PatternStorage.SavedPattern> nonFavorites = new ArrayList<>();

        for (PatternStorage.SavedPattern pattern : patterns) {
            if (pattern.isFavorite()) {
                favorites.add(pattern);
            } else {
                nonFavorites.add(pattern);
            }
        }

        // Create order map
        Map<String, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            orderMap.put(order.get(i), i);
        }

        // Sort based on order
        boolean isSpecialFolder = actualLocation.equals(PatternStorage.TRASH_FOLDER) ||
                actualLocation.equals(PatternStorage.CASTED_FOLDER);

        Comparator<PatternStorage.SavedPattern> comparator = (a, b) -> {
            Integer indexA = orderMap.get(a.getName());
            Integer indexB = orderMap.get(b.getName());

            if (isSpecialFolder) {
                if (indexA == null) indexA = -1;
                if (indexB == null) indexB = -1;
            } else {
                // For patterns not in order, maintain their file system order
                if (indexA == null) indexA = Integer.MAX_VALUE;
                if (indexB == null) indexB = Integer.MAX_VALUE;
            }

            return Integer.compare(indexA, indexB);
        };

        favorites.sort(comparator);
        nonFavorites.sort(comparator);

        // Combine: favorites first, then non-favorites
        List<PatternStorage.SavedPattern> result = new ArrayList<>();
        result.addAll(favorites);
        result.addAll(nonFavorites);

        return result;
    }

    private void loadSelectedPattern() {
        if (selectedPatternIndex >= 0) {
            PatternStorage.SavedPattern pattern = patternList.getSelectedPattern();
            if (pattern != null) {
                PatternStorage.loadPattern(pattern);
                this.onClose();
            }
        }
    }

    private void deleteSelectedPattern() {
        if (selectedPatternIndex >= 0) {
            PatternStorage.SavedPattern pattern = patternList.getSelectedPattern();
            if (pattern != null) {
                // Store information before deletion
                double currentScroll = patternList.getScrollAmount();
                int deletedIndex = selectedPatternIndex;
                int totalItems = patternList.children().size();
                int itemHeight = PATTERN_ITEM_HEIGHT;

                // Store the pattern name that will be selected after deletion
                String nextSelectionName = null;
                List<PatternStorage.SavedPattern> currentPatterns = patternList != null ? patternList.getAllPatterns() : null;

                if (currentPatterns != null && !currentPatterns.isEmpty()) {
                    // Always try next item first, then previous
                    if (deletedIndex < currentPatterns.size() - 1) {
                        nextSelectionName = currentPatterns.get(deletedIndex + 1).getName();
                    } else if (deletedIndex > 0) {
                        nextSelectionName = currentPatterns.get(deletedIndex - 1).getName();
                    }
                }

                // Calculate viewport information
                double viewportTop = currentScroll;
                double viewportHeight = patternList.getHeight();
                double deletedItemTop = deletedIndex * itemHeight;
                double deletedItemBottom = deletedItemTop + itemHeight;

                // Perform the deletion
                if (currentCategory == PatternCategory.TRASH) {
                    // Permanently delete
                    PatternStorage.permanentlyDeletePattern(pattern);
                } else {
                    // Move to trash
                    PatternStorage.movePattern(pattern, PatternStorage.TRASH_FOLDER, null);
                }

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

                // Update the list
                updatePatternList();

                // Force the scroll to where we want it
                if (patternList.children().size() > 0) {
                    patternList.setScrollAmount(newScroll);

                    // Auto-select next pattern
                    int newItemCount = patternList.children().size();
                    if (newItemCount > 0) {
                        if (nextSelectionName != null) {
                            // Find the pattern by name
                            List<PatternStorage.SavedPattern> updatedPatterns = patternList.getAllPatterns();
                            for (int i = 0; i < updatedPatterns.size(); i++) {
                                if (updatedPatterns.get(i).getName().equals(nextSelectionName)) {
                                    selectedPatternIndex = i;
                                    patternList.setSelected(patternList.children().get(i));
                                    break;
                                }
                            }
                        }
                        // If not found by name, select by position
                        if (selectedPatternIndex == -1) {
                            selectedPatternIndex = Math.min(deletedIndex, newItemCount - 1);
                            patternList.setSelected(patternList.children().get(selectedPatternIndex));
                        }
                    } else {
                        selectedPatternIndex = -1;
                    }
                } else {
                    selectedPatternIndex = -1;
                }

                updateButtonStates();
            }
        }
    }

    public PatternListWidget getPatternList() {
        return this.patternList;
    }

    public PatternCategory getCurrentCategory() {
        return this.currentCategory;
    }

    public int getPatternIndexAt(double mouseY) {
        if (patternList != null) {
            return patternList.getIndexAtPosition(mouseY);
        }
        return -1;
    }

    // Methods to check button hover states
    public boolean isPatternsButtonHovered(double mouseX, double mouseY) {
        return patternsButton != null && patternsButton.isMouseOver(mouseX, mouseY);
    }

    public boolean isTrashButtonHovered(double mouseX, double mouseY) {
        return trashButton != null && trashButton.isMouseOver(mouseX, mouseY);
    }

    public boolean isCastedButtonHovered(double mouseX, double mouseY) {
        return castedButton != null && castedButton.isMouseOver(mouseX, mouseY);
    }

    public boolean isLeftFolderButtonHovered(double mouseX, double mouseY) {
        return folderLeftButton != null && folderLeftButton.visible && folderLeftButton.isMouseOver(mouseX, mouseY);
    }

    public boolean isRightFolderButtonHovered(double mouseX, double mouseY) {
        return folderRightButton != null && folderRightButton.visible && folderRightButton.isMouseOver(mouseX, mouseY);
    }

    // Get button for hover feedback rendering
    public Button getCategoryButton(PatternCategory category) {
        switch (category) {
            case PATTERNS: return patternsButton;
            case TRASH: return trashButton;
            case CASTED: return castedButton;
            default: return null;
        }
    }

    // Switch category while maintaining drag state
    public void switchCategoryWithDrag(PatternCategory newCategory) {
        // Don't clear search or reset scroll when switching during drag
        this.currentCategory = newCategory;

        // Update pattern list without clearing drag state
        List<PatternStorage.SavedPattern> patterns;
        String searchTerm = searchBox != null ? searchBox.getValue().toLowerCase() : "";

        switch (currentCategory) {
            case TRASH:
                patterns = PatternStorage.getTrashedPatterns();
                patterns = sortPatternsByOrder(patterns);  // Use order-based sorting
                break;
            case CASTED:
                patterns = PatternStorage.getCastedPatterns();
                patterns = sortPatternsByOrder(patterns);  // Use order-based sorting
                break;
            default:
                patterns = PatternStorage.getSavedPatterns(currentFolder);
                patterns = sortPatternsByOrder(patterns);
                break;
        }

        if (!searchTerm.isEmpty()) {
            patterns = patterns.stream()
                    .filter(p -> p.getName().toLowerCase().contains(searchTerm))
                    .collect(Collectors.toList());
        }

        patternList.updatePatterns(patterns);
        updateButtonStates();
    }

    // Navigate folders while dragging
    public void navigateFolderLeft() {
        if (currentCategory != PatternCategory.PATTERNS) return;

        List<String> folders = availableFolders.stream()
                .filter(f -> !f.startsWith("."))
                .collect(Collectors.toList());
        folders.remove("default");
        folders.add(0, "default");

        int idx = folders.indexOf(currentFolder);
        if (idx > 0) {
            currentFolder = folders.get(idx - 1);
            PatternStorage.setLastUsedFolder(currentFolder);
            updateFolderNavigation(this.width / 2, 40); // Assuming Y position

            // Update list without resetting drag
            List<PatternStorage.SavedPattern> patterns = PatternStorage.getSavedPatterns(currentFolder);
            patterns = sortPatternsByOrder(patterns);
            patternList.updatePatterns(patterns);
        }
    }

    public void navigateFolderRight() {
        if (currentCategory != PatternCategory.PATTERNS) return;

        List<String> folders = availableFolders.stream()
                .filter(f -> !f.startsWith("."))
                .collect(Collectors.toList());
        folders.remove("default");
        folders.add(0, "default");

        int idx = folders.indexOf(currentFolder);
        if (idx < folders.size() - 1) {
            currentFolder = folders.get(idx + 1);
            PatternStorage.setLastUsedFolder(currentFolder);
            updateFolderNavigation(this.width / 2, 40); // Assuming Y position

            // Update list without resetting drag
            List<PatternStorage.SavedPattern> patterns = PatternStorage.getSavedPatterns(currentFolder);
            patterns = sortPatternsByOrder(patterns);
            patternList.updatePatterns(patterns);
        }
    }

    // Reorder pattern within the same folder
    // Update reorderPattern method
    public void reorderPattern(int fromIndex, int toIndex) {
        if (fromIndex == toIndex) {
            return;
        }

        List<PatternStorage.SavedPattern> patterns = new ArrayList<>(patternList.getAllPatterns());

        if (fromIndex >= 0 && fromIndex < patterns.size() &&
                toIndex >= 0 && toIndex < patterns.size()) {

            PatternStorage.SavedPattern draggedPattern = patterns.get(fromIndex);

            // Check favorite constraints
            boolean isDraggedFavorite = draggedPattern.isFavorite();
            int totalFavorites = 0;
            for (PatternStorage.SavedPattern p : patterns) {
                if (p.isFavorite()) {
                    totalFavorites++;
                }
            }

            // Ensure non-favorites can't be placed above favorites
            if (!isDraggedFavorite && toIndex < totalFavorites - (draggedPattern.isFavorite() ? 0 : 1)) {
                toIndex = totalFavorites - 1;
            }

            // Perform the reorder
            patterns.remove(fromIndex);
            patterns.add(toIndex, draggedPattern);

            // Update the pattern order
            List<String> order = patterns.stream()
                    .map(PatternStorage.SavedPattern::getName)
                    .collect(Collectors.toList());

            String actualLocation = getActualCurrentLocation();
            Map<String, List<String>> fullPatternOrder = PatternStorage.loadPatternOrder();
            fullPatternOrder.put(actualLocation, order);
            PatternStorage.savePatternOrder(fullPatternOrder);
            this.patternOrder = fullPatternOrder;

            // Update display
            patternList.updatePatterns(patterns);
        }
    }

    // Update onPatternSaved method
    public void onPatternSaved(String patternName) {
        String actualLocation = getActualCurrentLocation();
        List<String> order = patternOrder.getOrDefault(actualLocation, new ArrayList<>());

        order.remove(patternName);

        // CASTED gets new patterns at the top (most recent casts first)
        if (currentCategory == PatternCategory.CASTED) {
            // Count favorites to insert after them
            List<PatternStorage.SavedPattern> allPatterns = PatternStorage.getCastedPatterns();
            int favoriteCount = 0;
            for (PatternStorage.SavedPattern p : allPatterns) {
                if (p.isFavorite()) favoriteCount++;
            }
            order.add(favoriteCount, patternName);  // Add after favorites, but before other patterns
        } else {
            // Regular folders get new patterns at the bottom
            order.add(patternName);
        }

        patternOrder.put(actualLocation, order);
        PatternStorage.savePatternOrder(patternOrder);
        updatePatternList();
    }


    // Get font for rendering
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    public void onPatternSelected(int index) {
        this.selectedPatternIndex = index;
        updateButtonStates();
    }

    public void onPatternRightClicked(int index, int mouseX, int mouseY) {
        this.selectedPatternIndex = index;
        PatternStorage.SavedPattern pattern = patternList.getSelectedPattern();
        if (pattern != null) {
            contextMenu.show(mouseX, mouseY, pattern);
        }
    }

    public void toggleFavorite(PatternStorage.SavedPattern pattern) {
        // Store scroll before toggle
        double previousScroll = patternList != null ? patternList.getScrollAmount() : 0;

        boolean wasStarred = pattern.isFavorite();
        pattern.setFavorite(!pattern.isFavorite());

        // If we just starred it, move it to the bottom of starred patterns
        if (!wasStarred && pattern.isFavorite()) {
            // Get current order
            String actualLocation = getActualCurrentLocation();
            Map<String, List<String>> orderMap = PatternStorage.loadPatternOrder();
            List<String> order = orderMap.getOrDefault(actualLocation, new ArrayList<>());

            // Count existing favorites
            List<PatternStorage.SavedPattern> allPatterns;
            switch (currentCategory) {
                case TRASH:
                    allPatterns = PatternStorage.getTrashedPatterns();
                    break;
                case CASTED:
                    allPatterns = PatternStorage.getCastedPatterns();
                    break;
                default:
                    allPatterns = PatternStorage.getSavedPatterns(currentFolder);
                    break;
            }

            int favoriteCount = 0;
            for (PatternStorage.SavedPattern p : allPatterns) {
                if (p.isFavorite() && !p.getName().equals(pattern.getName())) {
                    favoriteCount++;
                }
            }

            // Remove the pattern from its current position
            order.remove(pattern.getName());

            // Insert at the end of favorites (position = favoriteCount)
            order.add(favoriteCount, pattern.getName());

            // Save the new order
            orderMap.put(actualLocation, order);
            PatternStorage.savePatternOrder(orderMap);
        }

        updatePatternList(); // Will automatically preserve scroll now

        // Since favorites move to top, we might need to adjust scroll
        if (pattern.isFavorite() && previousScroll > 0) {
            // If we just favorited something, smoothly scroll to show it
            patternList.setScrollAmount(0);
        } else {
            // Otherwise maintain position
            patternList.setScrollAmount(previousScroll);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // Let the default scrolling happen first
        boolean handled;
        handled = super.mouseScrolled(mouseX, mouseY, delta);
        return handled;
    }

    @Override
    public void tick() {
        super.tick();

        if (dragDropManager != null) {
            dragDropManager.tick();
        }

        if (searchBox != null) {
            searchBox.tick();

            String currentValue = searchBox.getValue();

            if (!currentValue.equals(pendingSearchTerm)) {
                pendingSearchTerm = currentValue;
                lastSearchTerm = currentValue;
                updatePatternList();
            }
        }

        contextMenu.tick();

        if (isHoldingEmptyTrash && emptyTrashButton != null) {
            double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();

            if (!emptyTrashButton.isMouseOver(mouseX, mouseY)) {
                isHoldingEmptyTrash = false;
                if (emptyTrashMaxProgress > 0) {
                    emptyTrashReleaseTime = System.currentTimeMillis();
                }
                emptyTrashHoldStartTime = -1;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
        // Simple dark gradient background, no bounding box
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && emptyTrashButton != null && emptyTrashButton.visible &&
                emptyTrashButton.active && emptyTrashButton.isMouseOver(mouseX, mouseY)) {

            List<PatternStorage.SavedPattern> trashPatterns = PatternStorage.getTrashedPatterns();
            if (!trashPatterns.isEmpty()) {
                isHoldingEmptyTrash = true;

                // If we're resuming from a reverse animation, use the actual current progress
                if (emptyTrashReleaseTime > 0 && emptyTrashCurrentProgress > 0) {
                    // Set start time as if we had been holding for the equivalent duration
                    emptyTrashHoldStartTime = System.currentTimeMillis() - (long) (emptyTrashCurrentProgress * EMPTY_TRASH_HOLD_DURATION);
                    emptyTrashMaxProgress = emptyTrashCurrentProgress;
                    emptyTrashReleaseTime = -1; // Clear reverse animation state
                } else {
                    // Starting fresh
                    emptyTrashHoldStartTime = System.currentTimeMillis();
                    // Keep existing emptyTrashMaxProgress if any
                }
            }
            return true;
        }

        if (contextMenu.isVisible()) {
            if (contextMenu.handleClick(mouseX, mouseY)) {
                return true;
            } else {
                contextMenu.hide();
                return true;
            }
        }

        if (dragDropManager.handleMouseClick(mouseX, mouseY, button)) {
            return true;
        }

        if (searchBox != null && searchBox.isFocused()) {
            if (!searchBox.isMouseOver(mouseX, mouseY)) {
                searchBox.setFocused(false);
                if (!pendingSearchTerm.equals(lastSearchTerm)) {
                    lastSearchTerm = pendingSearchTerm;
                    updatePatternList();
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Check if dragging the scrollbar FIRST
        if (button == 0 && patternList != null && patternList.isMouseOverScrollbar(mouseX, mouseY)) {
            // Let the scrollbar handle it
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        // Let drag manager handle dragging
        if (dragDropManager.handleMouseDrag(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && isHoldingEmptyTrash) {
            boolean wasOverButton = emptyTrashButton != null &&
                    emptyTrashButton.isMouseOver(mouseX, mouseY);

            if (wasOverButton && emptyTrashHoldStartTime > 0) {
                long holdDuration = System.currentTimeMillis() - emptyTrashHoldStartTime;

                if (holdDuration >= EMPTY_TRASH_HOLD_DURATION && emptyTrashMaxProgress >= 0.99f) {
                    // Only delete if we actually reached full progress
                    List<PatternStorage.SavedPattern> trashPatterns = PatternStorage.getTrashedPatterns();
                    if (!trashPatterns.isEmpty()) {  // Double-check patterns exist
                        for (PatternStorage.SavedPattern pattern : trashPatterns) {
                            PatternStorage.permanentlyDeletePattern(pattern);
                        }
                        updatePatternList();
                    }
                    emptyTrashMaxProgress = 0f;
                    emptyTrashReleaseTime = -1;
                } else {
                    emptyTrashReleaseTime = System.currentTimeMillis();
                }
            } else {
                if (emptyTrashMaxProgress > 0) {
                    emptyTrashReleaseTime = System.currentTimeMillis();
                }
            }

            isHoldingEmptyTrash = false;
            emptyTrashHoldStartTime = -1;
            return true;
        }

        if (dragDropManager.handleMouseRelease(mouseX, mouseY, button)) {
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        dragDropManager.handleMouseMove(mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Check if we're returning from a dialog and restore scroll
        if (returningFromDialog && preservedScrollPosition > 0) {
            restoreScroll();
            returningFromDialog = false;
        }

        // Render our custom background first
        this.renderBackground(guiGraphics);

        // Render folder label OR category name
        if (folderLeftButton != null && folderRightButton != null) {
            Component labelToRender = null;

            if (currentCategory == PatternCategory.PATTERNS) {
                // Show folder name for PATTERNS
                labelToRender = folderLabel;
            } else if (currentCategory == PatternCategory.TRASH) {
                // Show "Trash" label
                labelToRender = Component.literal("Trash");
            } else if (currentCategory == PatternCategory.CASTED) {
                // Show "Casted" label
                labelToRender = Component.literal("Casted");
            }

            if (labelToRender != null) {
                // Calculate the exact center between the two buttons
                int leftButtonEnd = folderLeftButton.getX() + folderLeftButton.getWidth();
                int rightButtonStart = folderRightButton.getX();
                int availableSpace = rightButtonStart - leftButtonEnd;
                int labelWidth = this.font.width(labelToRender);

                // Center the label in the available space between buttons
                int labelX = leftButtonEnd + (availableSpace - labelWidth) / 2;
                int labelY = folderNavigationY + (20 - font.lineHeight) / 2;

                // Draw the label
                guiGraphics.drawString(this.font, labelToRender, labelX, labelY, 0xFFFFFF);

                // Tooltip for truncated folder names (only for PATTERNS category)
                if (currentCategory == PatternCategory.PATTERNS &&
                        currentFolder.length() > ValidationConstants.MAX_FOLDER_NAME_LENGTH) {
                    // Check if mouse is over the folder name area
                    if (mouseX >= leftButtonEnd && mouseX <= rightButtonStart &&
                            mouseY >= folderNavigationY && mouseY <= folderNavigationY + 20) {
                        // Render tooltip with full folder name
                        guiGraphics.renderTooltip(this.font, Component.literal(currentFolder), mouseX, mouseY);
                    }
                }
            }
        }

        // Render all widgets (including pattern list with its tooltips)
        for (var widget : this.renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // Render drag visuals (insertion indicators, etc.)
        dragDropManager.render(guiGraphics, mouseX, mouseY, partialTick);

        // Render context menu ABSOLUTELY LAST - after all widgets and drag visuals
        if (contextMenu.isVisible()) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 500); // Push to front

            contextMenu.render(guiGraphics, mouseX, mouseY);

            guiGraphics.pose().popPose();
        }
    }

    // Custom EditBox to prevent default background rendering
    private static class PatternSearchBox extends EditBox {
        private final PatternManagerScreen parent;
        private String lastValue = "";

        public PatternSearchBox(net.minecraft.client.gui.Font font, int x, int y, int width, int height, Component message, PatternManagerScreen parent) {
            super(font, x, y, width, height, message);
            this.setBordered(true);
            this.parent = parent;
            this.setMaxLength(ValidationConstants.MAX_FOLDER_NAME_LENGTH);
            this.setFilter(input -> input.matches("^[a-zA-Z0-9._\\- ]*$"));
            this.lastValue = "";

            // Clear history on new search box
            searchHistory.clear();
            historyIndex = -1;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            if (this.isVisible()) {
                guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, 0xFF1A1A1A);
                super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public void tick() {
            super.tick();

            String currentValue = this.getValue();
            if (!currentValue.equals(lastValue)) {
                // Detect significant deletions
                if (!lastValue.isEmpty() && (
                        lastValue.length() - currentValue.length() >= 2 || // Multiple chars deleted
                                (currentValue.isEmpty() && lastValue.length() > 0))) { // All deleted

                    saveToHistory(lastValue);
                }
                lastValue = currentValue;
            }
        }

        private void saveToHistory(String text) {
            // Never save empty strings to history
            if (text.isEmpty()) {
                return;
            }

            // Don't save duplicates of the current position
            if (historyIndex >= 0 && historyIndex < searchHistory.size() &&
                    searchHistory.get(historyIndex).equals(text)) {
                return;
            }

            // If we're not at the end, truncate future history
            while (searchHistory.size() > historyIndex + 1) {
                searchHistory.remove(searchHistory.size() - 1);
            }

            searchHistory.add(text);
            historyIndex = searchHistory.size() - 1;

            // Limit size
            if (searchHistory.size() > MAX_HISTORY) {
                searchHistory.remove(0);
                historyIndex--;
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!this.isFocused()) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            boolean hasControl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
            boolean hasShift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

            // Handle Ctrl+Z for undo
            if (keyCode == GLFW.GLFW_KEY_Z && hasControl && !hasShift) {
                String current = this.getValue();

                // If current text is empty and we have history, restore the last item
                if (current.isEmpty() && !searchHistory.isEmpty() && historyIndex >= 0) {
                    String restoreText = searchHistory.get(Math.min(historyIndex, searchHistory.size() - 1));

                    super.setValue(restoreText);
                    this.moveCursorToEnd();
                    lastValue = restoreText;
                    parent.lastSearchTerm = restoreText;
                    parent.pendingSearchTerm = restoreText;
                    parent.updatePatternList();
                    return true;
                }

                // If we have text and haven't saved it yet, save it
                if (!current.isEmpty()) {
                    boolean needToSave = searchHistory.isEmpty() ||
                            historyIndex < 0 ||
                            historyIndex >= searchHistory.size() ||
                            !searchHistory.get(historyIndex).equals(current);

                    if (needToSave) {
                        saveToHistory(current);
                    }
                }

                // Standard undo: go to previous item in history
                if (historyIndex > 0) {
                    historyIndex--;
                    String previousText = searchHistory.get(historyIndex);

                    super.setValue(previousText);
                    this.moveCursorToEnd();
                    lastValue = previousText;
                    parent.lastSearchTerm = previousText;
                    parent.pendingSearchTerm = previousText;
                    parent.updatePatternList();
                    return true;
                } else if (historyIndex == 0 && !current.isEmpty()) {
                    // If at first item and field is not empty, clear it
                    historyIndex = -1;

                    super.setValue("");
                    lastValue = "";
                    parent.lastSearchTerm = "";
                    parent.pendingSearchTerm = "";
                    parent.updatePatternList();
                    return true;
                }
                return true;
            }

            // Handle Ctrl+Y or Ctrl+Shift+Z for redo
            if ((keyCode == GLFW.GLFW_KEY_Y && hasControl) ||
                    (keyCode == GLFW.GLFW_KEY_Z && hasControl && hasShift)) {

                if (historyIndex < searchHistory.size() - 1) {
                    historyIndex++;
                    String redoText = searchHistory.get(historyIndex);

                    super.setValue(redoText);
                    this.moveCursorToEnd();
                    lastValue = redoText;
                    parent.lastSearchTerm = redoText;
                    parent.pendingSearchTerm = redoText;
                    parent.updatePatternList();
                    return true;
                }
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 1 && this.isMouseOver(mouseX, mouseY)) {
                String current = this.getValue();
                if (!current.isEmpty()) {
                    saveToHistory(current);
                }

                super.setValue("");
                lastValue = "";
                parent.lastSearchTerm = "";
                parent.pendingSearchTerm = "";
                parent.updatePatternList();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

}