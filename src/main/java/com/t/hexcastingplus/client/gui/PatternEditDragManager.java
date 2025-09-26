package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import net.minecraft.client.gui.GuiGraphics;

public class PatternEditDragManager {
    private final PatternEditScreen screen;

    private boolean isDragging = false;
    private boolean fromEditArea = false;
    private boolean fromDisposalArea = false;
    private int draggedIndex = -1;
    private int insertionIndex = -1;
    private HexPattern draggedPattern = null;
    private double mouseX, mouseY;
    private String draggedDisplayText = null;

    public PatternEditDragManager(PatternEditScreen screen) {
        this.screen = screen;
    }

    public void startDragFromEdit(int index, double mouseX, double mouseY) {
        this.isDragging = true;
        this.fromEditArea = true;
        this.fromDisposalArea = false;
        this.draggedIndex = index;
        this.draggedPattern = screen.getEditingPatterns().get(index);
        this.draggedDisplayText = screen.getEditablePatterns().get(index).getDisplayText();
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.insertionIndex = index;
    }

    public void startDragFromDisposal(int index, double mouseX, double mouseY) {
        this.isDragging = true;
        this.fromEditArea = false;
        this.fromDisposalArea = true;
        this.draggedIndex = index;
        this.draggedPattern = screen.getDisposalPatterns().get(index);
        this.draggedDisplayText = screen.getDisposalEditablePatterns().get(index).getDisplayText();
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.insertionIndex = index;
    }

    public void updateDrag(double mouseX, double mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        // Calculate insertion index based on mouse position
        int editAreaY = 30 + PatternEditScreen.DISPOSAL_AREA_HEIGHT;

        if (mouseY < editAreaY) {
            // In disposal area
            updateDisposalInsertionIndex();
        } else {
            // In edit area
            updateEditInsertionIndex();
        }
    }

    private void updateDisposalInsertionIndex() {
        // Calculate dynamic patterns per row for disposal area
        int scrollbarSpace = 0; // Disposal area doesn't have scrollbar
        int availableWidth = screen.width - 30 - scrollbarSpace;
        int patternsPerRow = Math.max(1, availableWidth / (PatternEditScreen.PATTERN_SIZE + PatternEditScreen.PATTERN_SPACING));

        // Calculate container positioning (must match render method)
        int containerWidth = patternsPerRow * PatternEditScreen.PATTERN_SIZE + (patternsPerRow - 1) * PatternEditScreen.PATTERN_SPACING;
        int containerX = 11 + (availableWidth - containerWidth) / 2;

        // Calculate relative position within container
        double relativeX = mouseX - containerX;
        double relativeY = mouseY - 40; // 40 is the Y position of disposal area patterns

        if (relativeX < 0) {
            insertionIndex = 0;
            // Adjust for dragging within same area
            if (fromDisposalArea && draggedIndex < insertionIndex) {
                insertionIndex--;
            }
            return;
        }

        if (relativeY < 0) {
            insertionIndex = 0;
            // Adjust for dragging within same area
            if (fromDisposalArea && draggedIndex < insertionIndex) {
                insertionIndex--;
            }
            return;
        }

        // Calculate which row we're on
        int row = (int)(relativeY / (PatternEditScreen.PATTERN_SIZE + PatternEditScreen.PATTERN_SPACING));

        // Calculate position within the row
        double xInRow = relativeX;
        int patternSlotWidth = PatternEditScreen.PATTERN_SIZE + PatternEditScreen.PATTERN_SPACING;

        // Find which slot we're closest to
        int col = (int)((xInRow + PatternEditScreen.PATTERN_SPACING / 2.0) / patternSlotWidth);
        col = Math.max(0, Math.min(col, patternsPerRow));

        // Calculate the insertion index
        int index = row * patternsPerRow + col;

        // Cap at the size of disposal patterns
        index = Math.min(index, screen.getDisposalPatterns().size());

        // IMPORTANT: Adjust index if we're dragging from within the disposal area
        // and the insertion point is after the original position
        if (fromDisposalArea && index > draggedIndex) {
            // The visual insertion point should be adjusted because when we remove
            // the dragged item, everything after it shifts down by one
            insertionIndex = index - 1;
        } else {
            insertionIndex = index;
        }
    }

    private void updateEditInsertionIndex() {
        // Calculate dynamic patterns per row for edit area
        int availableWidth = screen.width - 30;
        // Check if scrollbar is visible and adjust width
        if (screen.getMaxScroll() > 0) {
            availableWidth -= (6 + 10); // SCROLLBAR_WIDTH + SCROLLBAR_MARGIN
        }
        int patternsPerRow = Math.max(1, availableWidth / (PatternEditScreen.PATTERN_SIZE + PatternEditScreen.PATTERN_SPACING));

        // Calculate container positioning (must match render method)
        int containerWidth = patternsPerRow * PatternEditScreen.PATTERN_SIZE + (patternsPerRow - 1) * PatternEditScreen.PATTERN_SPACING;
        int containerX = 11 + (availableWidth - containerWidth) / 2;

        int editAreaY = 30 + PatternEditScreen.DISPOSAL_AREA_HEIGHT;

        // IMPORTANT: Add scroll offset to get the actual content position
        double scrollOffset = screen.getScrollAmount();
        double relativeY = mouseY - editAreaY - 10 + scrollOffset; // 10 is EDIT_AREA_PADDING
        double relativeX = mouseX - containerX;

        if (relativeX < 0) {
            insertionIndex = 0;
            // Adjust for dragging within same area
            if (fromEditArea && draggedIndex < insertionIndex) {
                insertionIndex--;
            }
            return;
        }

        if (relativeY < 0) {
            insertionIndex = 0;
            // Adjust for dragging within same area
            if (fromEditArea && draggedIndex < insertionIndex) {
                insertionIndex--;
            }
            return;
        }

        // Calculate which row we're on
        int row = (int)(relativeY / (PatternEditScreen.PATTERN_SIZE + PatternEditScreen.PATTERN_SPACING));

        // Calculate position within the row
        double xInRow = relativeX;
        int patternSlotWidth = PatternEditScreen.PATTERN_SIZE + PatternEditScreen.PATTERN_SPACING;

        // Find which slot we're closest to
        int col = (int)((xInRow + PatternEditScreen.PATTERN_SPACING / 2.0) / patternSlotWidth);
        col = Math.max(0, Math.min(col, patternsPerRow));

        // Calculate the insertion index
        int index = row * patternsPerRow + col;

        // Cap at the size of editing patterns
        index = Math.min(index, screen.getEditingPatterns().size());

        // IMPORTANT: Adjust index if we're dragging from within the edit area
        // and the insertion point is after the original position
        insertionIndex = index;
    }

    public void completeDrag() {
        if (!isDragging) return;

        int editAreaY = 30 + PatternEditScreen.DISPOSAL_AREA_HEIGHT;
        boolean toDisposal = mouseY < editAreaY;
        boolean toEdit = mouseY >= editAreaY;

        if (fromEditArea && toDisposal) {
            // Moving from edit to disposal - just add to end of disposal
            screen.moveFromEditToDisposal(draggedIndex);
        } else if (fromDisposalArea && toEdit) {
            // Moving from disposal to edit
            screen.moveFromDisposalToEdit(draggedIndex, insertionIndex);
        } else if (fromEditArea && toEdit) {
            // Reordering within edit area
            if (draggedIndex != insertionIndex) {
                int adjustedIndex = insertionIndex;
                if (draggedIndex < insertionIndex) {
                    // When moving right/down, adjust by 1 EXCEPT when moving to the very end
                    if (insertionIndex < screen.getEditingPatterns().size()) {
                        adjustedIndex = insertionIndex + 1;
                    }
                    // else: already at the end, no adjustment needed
                }
                screen.reorderInEdit(draggedIndex, adjustedIndex);
            }
        } else if (fromDisposalArea && toDisposal) {
            // Reordering within disposal area
            if (draggedIndex != insertionIndex) {
                int adjustedIndex = insertionIndex;
                if (draggedIndex < insertionIndex) {
                    // When moving right/down, adjust by 1 EXCEPT when moving to the very end
                    if (insertionIndex < screen.getDisposalPatterns().size()) {
                        adjustedIndex = insertionIndex + 1;
                    }
                    // else: already at the end, no adjustment needed
                }
                screen.reorderInDisposal(draggedIndex, adjustedIndex);
            }
        }
        reset();
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!isDragging || draggedPattern == null) return;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 400);

        int size = PatternEditScreen.PATTERN_SIZE;
        int x = mouseX - size / 2;
        int y = mouseY - size / 2;

        guiGraphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000);

        if (draggedDisplayText != null) {
            renderDraggedNumber(guiGraphics, draggedDisplayText, x, y, size);
        } else {
            PatternRenderer.renderPattern(guiGraphics, draggedPattern, x, y, size, size,
                    PatternRenderer.RenderStyle.STATIC, new PatternRenderer.RenderOptions().withFadeAlpha(0.7f), true);
        }

        guiGraphics.pose().popPose();
    }

    private void renderDraggedNumber(GuiGraphics guiGraphics, String number, int x, int y, int size) {
        // Add "N:" prefix to the number
        String displayText = "N:" + number;

        var font = net.minecraft.client.Minecraft.getInstance().font;
        int textWidth = font.width(displayText);
        int textHeight = font.lineHeight;

        int maxWidth = size - 4;
        float scale = 1.0f;

        if (textWidth > maxWidth) {
            scale = (float)maxWidth / textWidth;
            scale = Math.max(scale, 0.5f);
        }

        float centerX = x + size / 2.0f;
        float centerY = y + size / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX, centerY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        int drawX = -textWidth / 2;
        int drawY = -textHeight / 2;

        guiGraphics.drawString(font, displayText, drawX, drawY, 0xFFFFFF);

        guiGraphics.pose().popPose();
    }

    private void reset() {
        isDragging = false;
        fromEditArea = false;
        fromDisposalArea = false;
        draggedIndex = -1;
        insertionIndex = -1;
        draggedPattern = null;
        draggedDisplayText = null;
    }

    public boolean isDragging() { return isDragging; }
    public boolean isDraggingFromEdit() { return isDragging && fromEditArea; }
    public boolean isDraggingFromDisposal() { return isDragging && fromDisposalArea; }
    public boolean isDraggingToEdit() {
        int editAreaY = 30 + PatternEditScreen.DISPOSAL_AREA_HEIGHT;
        return isDragging && mouseY >= editAreaY;
    }
    public boolean isDraggingToDisposal() {
        int editAreaY = 30 + PatternEditScreen.DISPOSAL_AREA_HEIGHT;
        return isDragging && mouseY < editAreaY;
    }
    public int getDraggedIndex() { return draggedIndex; }
    public int getInsertionIndex() { return insertionIndex; }
}