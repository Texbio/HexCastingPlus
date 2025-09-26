package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

import java.util.*;

/**
 * Manages all drag and drop operations for the Pattern Manager
 */
public class DragDropManager {
    private final PatternManagerScreen screen;

    // Drag state
    private DragState state = new DragState();

    // Configuration
    private static final long HOVER_SWITCH_DELAY = 200; // ms before switching category/folder
    private static final float GHOST_SCALE = 0.5f;
    private static final float GHOST_ALPHA = 0.7f;
    private static final int GHOST_SIZE = 60;
    private static final double SCROLL_ZONE_SIZE = 40;
    private static final double AUTO_SCROLL_MULTIPLIER = 1; // 0.2 = 5x slower, 1.0 = normal speed
    private static final double SCROLL_ZONE_PERCENTAGE = 0.49; // % of half of screen height for acceleration upper and lower zones, 0.1 to 0.49
    private static final double SCROLL_BOUNDRY_MULTIPLIER = 2; // increase multiplier closer to edges 1.1 to 3
    private static final int SCREEN_EDGE_MARGIN = -20; // Changed from 20px to 10px
    private static final double DRAG_THRESHOLD = 10.0; // Minimum pixels to move before starting drag


    // DO NOT CHANGE
    private static final double THRESHOLD_PERCENTAGE = 0.25; // 25% into adjacent item
    private static final int OFFSET_UP = -20;    // Positive = trigger earlier, Negative = trigger later
    private static final int OFFSET_DOWN = 20;  // Positive = trigger earlier, Negative = trigger later

    private int draggedItemIndex = -1;  // Which item is being dragged
    private String sourceLocation = ""; // Source folder/category



    public int getDraggedItemIndex() {
        if (state.isActive) {
            long now = System.currentTimeMillis();
            if (now - state.lastGetIndexDebugTime > 1000) { // Once per second
                state.lastGetIndexDebugTime = now;
            }
            return state.originalIndex;
        }
        return -1;
    }
    public int getGapPosition() {
        if (state.isActive) {
            long now = System.currentTimeMillis();
            if (now - state.lastRenderDebugTime > 1000) { // Print once per second
                state.lastRenderDebugTime = now;
            }
            return state.insertionIndex;
        }
        return -1;
    }
    public boolean isDragging() {
        return state.isActive;
    }
    public String getSourceLocation() {
        return state.sourceLocation;
    }

    public DragDropManager(PatternManagerScreen screen) {
        this.screen = screen;
    }

    /**
     * Complete drag state information
     */
    private static class DragState {
        long lastGetIndexDebugTime = 0;

        double currentRelativeY = 0;
        double lastUpThreshold = 0;
        double lastDownThreshold = 0;

        // Track if initial mouse press was on a draggable area
        boolean dragAllowed = false;
        double initialClickX = 0;
        double initialClickY = 0;

        // Core state
        boolean isActive = false;
        boolean rightClickHeld = false;
        long lastRenderDebugTime = 0;

        // Dragged pattern info
        PatternStorage.SavedPattern draggedPattern = null;
        String sourceLocation = null;
        int originalIndex = -1;

        // Current drag position
        double mouseX = 0;
        double mouseY = 0;
        int insertionIndex = -1;

        // Navigation hover tracking
        long hoverStartTime = 0;
        Object hoveredTarget = null;

        // Folder navigation cooldown
        long lastFolderSwitch = 0;
        static final long FOLDER_SWITCH_COOLDOWN = 300;

        // Visual state
        float animationProgress = 0;
        long dragStartTime = 0;

        // Auto-scroll acceleration
        double scrollSpeed = 0;
        long scrollStartTime = 0;

        // Right-click drag detection
        PatternStorage.SavedPattern potentialDragPattern = null;
        int potentialDragIndex = -1;

        // Debug throttling
        long lastDebugPrintTime = 0;

        void reset() {
            dragAllowed = false;
            initialClickX = 0;
            initialClickY = 0;
            isActive = false;
            draggedPattern = null;
            sourceLocation = null;
            originalIndex = -1;
            insertionIndex = -1;
            hoveredTarget = null;
            hoverStartTime = 0;
            animationProgress = 0;
            scrollSpeed = 0;
            scrollStartTime = 0;
            potentialDragPattern = null;
            potentialDragIndex = -1;
            lastDebugPrintTime = 0;
        }
    }

    /**
     * Update method that should be called every frame/tick
     * This ensures hover timers work even when mouse is stationary
     */
    public void tick() {
        if (state.isActive) {
            // Check navigation hover even when mouse isn't moving
            checkNavigationHover(state.mouseX, state.mouseY);

            // Handle auto-scrolling continuously (ADD THIS)
            handleAutoScroll(state.mouseY);

            // Update animation progress
            long elapsed = System.currentTimeMillis() - state.dragStartTime;
            state.animationProgress = Math.min(1.0f, elapsed / 200.0f);
        }
    }

    /**
     * Handle mouse button press
     */
    public boolean handleMouseClick(double mouseX, double mouseY, int button) {
        if (button == 0) { // Left click
            state.initialClickX = mouseX;
            state.initialClickY = mouseY;

            // Determine if this click position allows dragging
            state.dragAllowed = isDraggablePosition(mouseX, mouseY);

            // Don't consume the click - let it propagate to buttons
            return false;
        }

        if (button == 1) { // Right click
            // Store initial position for right-click drag threshold check
            state.initialClickX = mouseX;
            state.initialClickY = mouseY;

            PatternStorage.SavedPattern pattern = getPatternAt(mouseX, mouseY);
            if (pattern != null) {
                // Check if clicking on interactive elements that should handle right-click
                PatternListWidget list = screen.getPatternList();
                if (list != null) {
                    int entryX = list.getRowLeft();
                    int entryWidth = list.getRowWidth();

                    // Dots menu area - let it handle the right-click
                    if (mouseX >= entryX + entryWidth - 20 && mouseX <= entryX + entryWidth - 5) {
                        return false; // Don't start drag, let the menu handle it
                    }
                }

                // Otherwise prepare for potential drag
                state.rightClickHeld = true;
                state.potentialDragPattern = pattern;
                state.potentialDragIndex = screen.getPatternIndexAt(mouseY);
                state.mouseX = mouseX;
                state.mouseY = mouseY;

                // Return false to allow the right-click to propagate to the pattern entry
                return false; // Changed from true to false
            }
            // Right-click on empty space - don't set up for drag
            state.rightClickHeld = false;
            state.potentialDragPattern = null;
            state.potentialDragIndex = -1;
        }
        return false;
    }

    private boolean isDraggablePosition(double mouseX, double mouseY) {
        PatternListWidget list = screen.getPatternList();
        if (list == null || !list.isMouseOver(mouseX, mouseY)) {
            return false;
        }

        // Check if on scrollbar
        if (list.isMouseOverScrollbar(mouseX, mouseY)) {
            return false;
        }

        // Get the pattern index at this position
        int patternIndex = screen.getPatternIndexAt(mouseY);
        if (patternIndex < 0) {
            return false;
        }

        // Calculate the entry bounds
        int entryX = list.getRowLeft();
        int entryWidth = list.getRowWidth();
        int itemHeight = PatternManagerScreen.getPatternItemHeight();
        int entryY = list.getTop() + (patternIndex * itemHeight) - (int)list.getScrollAmount();

        // Check dots button bounds
        int dotsButtonX = entryX + entryWidth - PatternListWidget.DOTS_BUTTON_SIZE - PatternListWidget.DOTS_BUTTON_OFFSET;
        int dotsButtonY = entryY + (itemHeight - PatternListWidget.DOTS_BUTTON_SIZE) / 2;

        if (mouseX >= dotsButtonX &&
                mouseX < dotsButtonX + PatternListWidget.DOTS_BUTTON_SIZE &&
                mouseY >= dotsButtonY &&
                mouseY < dotsButtonY + PatternListWidget.DOTS_BUTTON_SIZE) {
            return false; // Click is on dots button
        }

        // Check star button area
        int starX = entryX + 5;
        int starWidth = 12;
        int fontHeight = screen.getFont().lineHeight;
        int starY = entryY + (itemHeight - fontHeight) / 2;

        if (mouseX >= starX &&
                mouseX <= starX + starWidth &&
                mouseY >= starY &&
                mouseY <= starY + fontHeight) {
            return false; // Click is on star
        }

        // This position allows dragging
        return true;
    }

    /**
     * Handle mouse button release
     */
    public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
        boolean handled = false;

        if (button == 0) { // Left button release
            state.dragAllowed = false; // Reset drag permission
            if (state.isActive) {
                completeDrop();
                handled = true;
            }
        }

        if (button == 1) {
            state.rightClickHeld = false;
            if (state.isActive) {
                completeDrop();
                handled = true;
            }
        }

        return handled;
    }

    /**
     * Handle mouse movement while dragging
     */
    public boolean handleMouseDrag(double mouseX, double mouseY, int button, double dragX, double dragY) {
        state.mouseX = mouseX;
        state.mouseY = mouseY;

        if (!state.isActive) {
            // Only start drag if:
            // 1. Left button (0) is being dragged
            // 2. Initial click was on a draggable area (dragAllowed = true)
            // 3. Not currently in right-click drag mode
            // 4. Mouse has moved at least DRAG_THRESHOLD pixels from initial position
            if (button == 0 && state.dragAllowed && !state.rightClickHeld) {
                // Check if we've moved enough to start dragging
                double dx = mouseX - state.initialClickX;
                double dy = mouseY - state.initialClickY;
                double distance = Math.sqrt(dx * dx + dy * dy);

                if (distance >= DRAG_THRESHOLD) {
                    // Use the stored initial click position to get the pattern
                    PatternStorage.SavedPattern pattern = getPatternAt(state.initialClickX, state.initialClickY);
                    int patternIndex = screen.getPatternIndexAt(state.initialClickY);

                    if (pattern != null && patternIndex >= 0) {
                        startDrag(pattern, patternIndex);
                        return true;
                    }
                }
            }
        } else {
            // Continue existing drag
            updateDragState(mouseX, mouseY);
            return true;
        }

        return false;
    }

    /**
     * Handle mouse movement (for right-click drag initiation)
     */
    public void handleMouseMove(double mouseX, double mouseY) {
        state.mouseX = mouseX;
        state.mouseY = mouseY;

        if (state.rightClickHeld && !state.isActive && state.potentialDragPattern != null) {
            // Start drag only if right-click began on a pattern
            // Use same DRAG_THRESHOLD for consistency
            double dx = mouseX - state.initialClickX;  // Note: need to store initial position for right-click too
            double dy = mouseY - state.initialClickY;
            if (Math.sqrt(dx * dx + dy * dy) >= DRAG_THRESHOLD) {
                startDrag(state.potentialDragPattern, state.potentialDragIndex);
            }
        }

        if (state.isActive) {
            updateDragState(mouseX, mouseY);
        }
    }

    private void startDrag(PatternStorage.SavedPattern pattern, int index) {
        state.isActive = true;
        state.draggedPattern = pattern;
        state.originalIndex = index;
        state.sourceLocation = screen.getActualCurrentLocation();
        state.dragStartTime = System.currentTimeMillis();
        state.animationProgress = 0;
        state.insertionIndex = index; // Gap starts where item was
    }

    private void updateDragState(double mouseX, double mouseY) {
        // Check navigation element hovering
        checkNavigationHover(mouseX, mouseY);

        // Update insertion point in the list
        updateInsertionPoint(mouseX, mouseY);

        // Update fade-in animation only
        long elapsed = System.currentTimeMillis() - state.dragStartTime;
        state.animationProgress = Math.min(1.0f, elapsed / 200.0f);
    }

    /**
     * Check if hovering over navigation elements and switch if needed
     */
    private void checkNavigationHover(double mouseX, double mouseY) {
        long currentTime = System.currentTimeMillis();
        Object newTarget = null;

        // Check category buttons
        if (screen.isPatternsButtonHovered(mouseX, mouseY)) {
            newTarget = PatternManagerScreen.PatternCategory.PATTERNS;
        } else if (screen.isTrashButtonHovered(mouseX, mouseY)) {
            newTarget = PatternManagerScreen.PatternCategory.TRASH;
        } else if (screen.isCastedButtonHovered(mouseX, mouseY)) {
            newTarget = PatternManagerScreen.PatternCategory.CASTED;
        } else if (screen.isLeftFolderButtonHovered(mouseX, mouseY)) {
            // Check cooldown for folder navigation
            if (currentTime - state.lastFolderSwitch >= DragState.FOLDER_SWITCH_COOLDOWN) {
                newTarget = "FOLDER_LEFT";
            }
        } else if (screen.isRightFolderButtonHovered(mouseX, mouseY)) {
            // Check cooldown for folder navigation
            if (currentTime - state.lastFolderSwitch >= DragState.FOLDER_SWITCH_COOLDOWN) {
                newTarget = "FOLDER_RIGHT";
            }
        }

        // Handle hover state changes
        if (newTarget != null) {
            if (!newTarget.equals(state.hoveredTarget)) {
                // Started hovering over a new target
                state.hoveredTarget = newTarget;
                state.hoverStartTime = currentTime;
            } else if (currentTime - state.hoverStartTime >= HOVER_SWITCH_DELAY) {
                // Been hovering long enough - execute switch
                executeNavigationSwitch(newTarget);

                // Update cooldown timer for folder switches
                if ("FOLDER_LEFT".equals(newTarget) || "FOLDER_RIGHT".equals(newTarget)) {
                    state.lastFolderSwitch = currentTime;
                }

                state.hoveredTarget = null; // Reset to prevent repeated switches
            }
        } else {
            // Not hovering over any navigation element
            state.hoveredTarget = null;
        }
    }

    /**
     * Execute navigation switch while maintaining drag
     */
    private void executeNavigationSwitch(Object target) {
        if (target instanceof PatternManagerScreen.PatternCategory) {
            PatternManagerScreen.PatternCategory category = (PatternManagerScreen.PatternCategory) target;
            if (category != screen.getCurrentCategory()) {
                screen.switchCategoryWithDrag(category);
                state.insertionIndex = -1; // Gap disappears
            }
        } else if ("FOLDER_LEFT".equals(target)) {
            screen.navigateFolderLeft();
            state.insertionIndex = -1; // Gap disappears
        } else if ("FOLDER_RIGHT".equals(target)) {
            screen.navigateFolderRight();
            state.insertionIndex = -1; // Gap disappears
        }
    }

    private void updateInsertionPoint(double mouseX, double mouseY) {
        PatternListWidget list = screen.getPatternList();
        if (list == null || !list.isMouseOver(mouseX, mouseY)) {
            return;
        }

        double relativeY = mouseY - list.getTop() + list.getScrollAmount();
        int itemHeight = PatternManagerScreen.getPatternItemHeight();
        int totalItems = list.getAllPatterns().size();

        if (state.insertionIndex == -1) {
            state.insertionIndex = state.originalIndex;
        }

        int newGapPosition = state.insertionIndex;

        // Check if we should move the gap up
        if (state.insertionIndex > 0) {
            double upThreshold = (state.insertionIndex - 1) * itemHeight + itemHeight * THRESHOLD_PERCENTAGE - OFFSET_UP;
            if (relativeY < upThreshold) {
                newGapPosition = state.insertionIndex - 1;
            }
        }

        // Check if we should move the gap down
        if (state.insertionIndex < totalItems) {
            double downThreshold = state.insertionIndex * itemHeight + itemHeight * (1 - THRESHOLD_PERCENTAGE) + OFFSET_DOWN;
            if (relativeY > downThreshold) {
                newGapPosition = state.insertionIndex + 1;
            }
        }

        if (newGapPosition != state.insertionIndex) {
            state.insertionIndex = newGapPosition;
        }
    }

    private void handleAutoScroll(double mouseY) {
        PatternListWidget list = screen.getPatternList();
        if (list == null) return;

        int listTop = list.getTop();
        int listBottom = list.getBottom();

        int screenHeight = screen.height;
        int zoneSize = (int)(screenHeight * SCROLL_ZONE_PERCENTAGE);

        // Acceleration starts at these boundaries
        int topZoneBoundary = zoneSize;
        int bottomZoneBoundary = screenHeight - zoneSize;

        // Max speed reached at these points
        int topMaxSpeed = SCREEN_EDGE_MARGIN;
        int bottomMaxSpeed = screenHeight - SCREEN_EDGE_MARGIN;

        double scrollSpeed = 0;

        // Check if mouse is in top acceleration zone
        if (mouseY < topZoneBoundary) {
            double maxDistance = topZoneBoundary - topMaxSpeed;
            double position;

            if (mouseY <= topMaxSpeed) {
                position = 1.0; // Max speed
            } else {
                position = (topZoneBoundary - mouseY) / maxDistance;
            }

            scrollSpeed = Math.pow(position, SCROLL_BOUNDRY_MULTIPLIER) * 1.0 * AUTO_SCROLL_MULTIPLIER;
            list.mouseScrolled(list.getLeft() + 10, listTop + 10, scrollSpeed);
        }
        // Check if mouse is in bottom acceleration zone
        else if (mouseY > bottomZoneBoundary) {
            double maxDistance = bottomMaxSpeed - bottomZoneBoundary;
            double position;

            if (mouseY >= bottomMaxSpeed) {
                position = 1.0; // Max speed
            } else {
                position = (mouseY - bottomZoneBoundary) / maxDistance;
            }

            scrollSpeed = Math.pow(position, 2) * 1.0 * AUTO_SCROLL_MULTIPLIER;
            list.mouseScrolled(list.getLeft() + 10, listBottom - 10, -scrollSpeed);
        }
    }

    public boolean isSourceIndex(int index, String location) {
        return state.isActive &&
                state.originalIndex == index &&
                state.sourceLocation.equals(location);
    }


    private void completeDrop() {
        if (!state.isActive || state.draggedPattern == null) {
            state.reset();
            return;
        }

        int gapPosition = state.insertionIndex;
        String sourceLocation = state.sourceLocation;
        String targetLocation = screen.getActualCurrentLocation();

        if (gapPosition < 0) {
            state.reset();
            return;
        }

        if (!sourceLocation.equals(targetLocation)) {
            // Cross-folder move - THIS IS WHERE THE BUG IS
            state.draggedPattern.setFolder(sourceLocation);

            // The movePattern method needs to actually use gapPosition
            PatternStorage.movePattern(state.draggedPattern, targetLocation, gapPosition);

        } else if (gapPosition != state.originalIndex) {
            // Same-folder reorder - KEEP YOUR ORIGINAL LOGIC
            int adjustedTarget = gapPosition;

            PatternListWidget list = screen.getPatternList();
            if (list != null) {
                int listSize = list.getAllPatterns().size();
                if (adjustedTarget > listSize) {
                    adjustedTarget = listSize;
                }
                adjustedTarget = Math.max(0, Math.min(adjustedTarget, listSize - 1));
            }

            screen.reorderPattern(state.originalIndex, adjustedTarget);
        }

        screen.updatePatternList();
        state.reset();
    }

    /**
     * Get pattern at mouse position
     */
    private PatternStorage.SavedPattern getPatternAt(double mouseX, double mouseY) {
        PatternListWidget list = screen.getPatternList();
        if (list != null && list.isMouseOver(mouseX, mouseY)) {
            int index = list.getIndexAtPosition(mouseY);
            if (index >= 0) {
                return list.getAllPatterns().get(index);
            }
        }
        return null;
    }

    /**
     * Render drag visuals
     */
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!state.isActive) return;

        // Render threshold lines for debugging
//        renderDebugLines(guiGraphics);

        // Render ghost pattern (existing code)
        renderGhostPattern(guiGraphics, mouseX, mouseY);

        // Render hover feedback on navigation elements
        renderNavigationHoverFeedback(guiGraphics);
    }

    private int getUpThreshold() {
        if (state.insertionIndex <= 0) return Integer.MIN_VALUE;
        int itemHeight = PatternManagerScreen.getPatternItemHeight();
        int itemAboveGap = state.insertionIndex - 1;
        return (int)(itemAboveGap * itemHeight + itemHeight * (1 - THRESHOLD_PERCENTAGE)) - OFFSET_UP;
    }

    private int getDownThreshold() {
        PatternListWidget list = screen.getPatternList();
        if (list == null || state.insertionIndex >= list.getAllPatterns().size() - 1) return Integer.MAX_VALUE;
        int itemHeight = PatternManagerScreen.getPatternItemHeight();
        int itemBelowGap = state.insertionIndex;
        return (int)((itemBelowGap + 1) * itemHeight - itemHeight * (1 - THRESHOLD_PERCENTAGE)) + OFFSET_DOWN;
    }

    // Replace renderDebugLines method:
    // Replace renderDebugLines method:
    private void renderDebugLines(GuiGraphics guiGraphics) {
        PatternListWidget list = screen.getPatternList();
        if (list == null || !state.isActive) return;

        int listTop = list.getTop();
        int listLeft = list.getLeft();
        int listWidth = list.getWidth();
        double scrollAmount = list.getScrollAmount();
        int itemHeight = PatternManagerScreen.getPatternItemHeight();
        int totalItems = list.getAllPatterns().size();

        // UP threshold: When mouse goes above this line, gap moves up
        if (state.insertionIndex > 0) {
            double upThresholdY = (state.insertionIndex - 1) * itemHeight + itemHeight * THRESHOLD_PERCENTAGE - OFFSET_UP;
            int upScreenY = (int)(listTop + upThresholdY - scrollAmount);

            if (upScreenY >= listTop && upScreenY <= list.getBottom()) {
                guiGraphics.fill(listLeft, upScreenY, listLeft + listWidth, upScreenY + 1, 0xFFFF0000);
                guiGraphics.drawString(screen.getFont(), "UP→" + (state.insertionIndex - 1), listLeft - 45, upScreenY - 4, 0xFFFF0000);
            }
        }

        // DOWN threshold: When mouse goes below this line, gap moves down
        if (state.insertionIndex < totalItems) {
            double downThresholdY = state.insertionIndex * itemHeight + itemHeight * (1 - THRESHOLD_PERCENTAGE) + OFFSET_DOWN;
            int downScreenY = (int)(listTop + downThresholdY - scrollAmount);

            if (downScreenY >= listTop && downScreenY <= list.getBottom()) {
                guiGraphics.fill(listLeft, downScreenY, listLeft + listWidth, downScreenY + 1, 0xFF00FF00);
                guiGraphics.drawString(screen.getFont(), "DN→" + (state.insertionIndex + 1), listLeft - 45, downScreenY - 4, 0xFF00FF00);
            }
        }

        // Draw the gap position itself (yellow)
        double gapY = state.insertionIndex * itemHeight;
        int gapScreenY = (int)(listTop + gapY - scrollAmount);
        if (gapScreenY >= listTop - itemHeight && gapScreenY <= list.getBottom() + itemHeight) {
            guiGraphics.fill(listLeft, gapScreenY - 2, listLeft + listWidth, gapScreenY + 2, 0xFFFFFF00);
            guiGraphics.drawString(screen.getFont(), "GAP@" + state.insertionIndex, listLeft - 45, gapScreenY - 4, 0xFFFFFF00);
        }

        // Mouse position (white)
        double relativeY = state.mouseY - listTop + scrollAmount;
        int mouseScreenY = (int)(state.mouseY);
        if (mouseScreenY >= listTop && mouseScreenY <= list.getBottom()) {
            guiGraphics.fill(listLeft, mouseScreenY - 1, listLeft + listWidth, mouseScreenY, 0xAAFFFFFF);
        }
    }


    /**
     * Render the ghost pattern following the mouse
     */
    private void renderGhostPattern(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (state.draggedPattern == null) return;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 400); // High Z-index

        // Calculate position
        int x = mouseX - GHOST_SIZE / 2;
        int y = mouseY - GHOST_SIZE / 2;

        // Animated fade-in
        float alpha = GHOST_ALPHA * state.animationProgress;

        // Semi-transparent background with border
        int bgAlpha = (int)(alpha * 0.8f * 255);
        guiGraphics.fill(x - 2, y - 2, x + GHOST_SIZE + 2, y + GHOST_SIZE + 2, (bgAlpha << 24) | 0x000000);
        guiGraphics.fill(x - 3, y - 3, x + GHOST_SIZE + 3, y - 2, 0x40FFFFFF); // Top border
        guiGraphics.fill(x - 3, y + GHOST_SIZE + 2, x + GHOST_SIZE + 3, y + GHOST_SIZE + 3, 0x40FFFFFF); // Bottom
        guiGraphics.fill(x - 3, y - 2, x - 2, y + GHOST_SIZE + 2, 0x40FFFFFF); // Left
        guiGraphics.fill(x + GHOST_SIZE + 2, y - 2, x + GHOST_SIZE + 3, y + GHOST_SIZE + 2, 0x40FFFFFF); // Right

        // Render patterns in a grid layout
        List<HexPattern> patterns = state.draggedPattern.getPatterns();
        PatternRenderer.RenderOptions options = new PatternRenderer.RenderOptions()
                .withFadeAlpha(alpha);

        // Calculate grid layout - up to 10x10
        int maxVisible = 100; // Max patterns to show (10x10)
        int count = Math.min(patterns.size(), maxVisible);

        if (count > 0) {
            // Calculate optimal grid size
            int gridSize = (int)Math.ceil(Math.sqrt(count));
            gridSize = Math.min(gridSize, 10); // Cap at 10x10

            int cellSize = GHOST_SIZE / gridSize;

            // Enable scissor to clip overflow
            guiGraphics.enableScissor(x, y, x + GHOST_SIZE, y + GHOST_SIZE);

            for (int i = 0; i < count; i++) {
                int row = i / gridSize;
                int col = i % gridSize;

                // No padding - use full cell size
                int cellX = x + col * cellSize;
                int cellY = y + row * cellSize;
                int patternMaxSize = cellSize;

                // Skip if too small
                if (patternMaxSize <= 3) continue;

                // Render with width constraint enabled
                PatternRenderer.renderPattern(
                        guiGraphics,
                        patterns.get(i),
                        cellX,
                        cellY,
                        patternMaxSize,
                        patternMaxSize,
                        PatternRenderer.RenderStyle.STATIC,
                        options,
                        true  // constrainWidth = true for ghost box
                );
            }

            guiGraphics.disableScissor();

            // Show "+N" indicator if there are more patterns
            if (patterns.size() > maxVisible) {
                String moreText = "+" + (patterns.size() - maxVisible);
                int moreX = x + GHOST_SIZE - screen.getFont().width(moreText) - 2;
                int moreY = y + GHOST_SIZE - 10;
                int moreAlpha = (int)(alpha * 255);
                int moreColor = (moreAlpha << 24) | 0xFFFF00;
                guiGraphics.drawString(screen.getFont(), moreText, moreX, moreY, moreColor);
            }
        }

        guiGraphics.pose().popPose();

        // Pattern name and count
        String label = state.draggedPattern.getName();
        if (patterns.size() > 1) {
            label += " [" + patterns.size() + "]";
        }

        int textAlpha = (int)(alpha * 255);
        int textColor = (textAlpha << 24) | 0xFFFFFF;
        guiGraphics.drawString(screen.getFont(), label, x, y + GHOST_SIZE + 5, textColor);

        // Show if it's favorited
        if (state.draggedPattern.isFavorite()) {
            guiGraphics.drawString(screen.getFont(), "★", x - 10, y + GHOST_SIZE + 5, 0xFFFFD700);
        }
    }

    /**
     * Render hover feedback on navigation elements
     */
    private void renderNavigationHoverFeedback(GuiGraphics guiGraphics) {
        if (state.hoveredTarget == null) return;

        long hoverTime = System.currentTimeMillis() - state.hoverStartTime;
        float progress = Math.min(1.0f, hoverTime / (float)HOVER_SWITCH_DELAY);

        // Draw progress indicator around hovered element
        if (progress > 0.1f) {
            int alpha = (int)(progress * 128);
            int color = (alpha << 24) | 0x00FF00;

            // Get bounds of hovered element
            int x, y, w, h;
            if (state.hoveredTarget instanceof PatternManagerScreen.PatternCategory) {
                Button button = screen.getCategoryButton((PatternManagerScreen.PatternCategory)state.hoveredTarget);
                if (button != null) {
                    x = button.getX();
                    y = button.getY();
                    w = button.getWidth();
                    h = button.getHeight();

                    // Draw progress border
                    int thickness = 2;
                    float len = (w + h) * 2 * progress;

                    // Top edge
                    if (len > 0) {
                        int topLen = (int)Math.min(w, len);
                        guiGraphics.fill(x, y - thickness, x + topLen, y, color);
                    }
                    // Right edge
                    if (len > w) {
                        int rightLen = (int)Math.min(h, len - w);
                        guiGraphics.fill(x + w, y, x + w + thickness, y + rightLen, color);
                    }
                    // Bottom edge
                    if (len > w + h) {
                        int bottomLen = (int)Math.min(w, len - w - h);
                        guiGraphics.fill(x + w - bottomLen, y + h, x + w, y + h + thickness, color);
                    }
                    // Left edge
                    if (len > w + h + w) {
                        int leftLen = (int)Math.min(h, len - w - h - w);
                        guiGraphics.fill(x - thickness, y + h - leftLen, x, y + h, color);
                    }
                }
            }
        }
    }

    public PatternStorage.SavedPattern getDraggedPattern() { return state.draggedPattern; }

    public int getInsertionIndex() { return state.insertionIndex; }
}