package com.t.hexcastingplus.client.gui;

import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.t.hexcastingplus.client.gui.SmoothScrollHelper;

import java.util.*;

public class PatternListWidget extends ObjectSelectionList<PatternListWidget.PatternEntry> {
    private final PatternManagerScreen parent;
    private List<PatternStorage.SavedPattern> patterns = new ArrayList<>();
    private final SmoothScrollHelper smoothScroller = new SmoothScrollHelper();

    private static final int PATTERN_PADDING = 2;

    //Threedots
    public static final int DOTS_BUTTON_SIZE = 16;
    public static final int DOTS_BUTTON_OFFSET = 5;
    private static final int DOTS_BUTTON_TOTAL_SPACE = DOTS_BUTTON_SIZE + DOTS_BUTTON_OFFSET + 5; // +5 for padding

    //Star
    private int renderedStarX = -1;
    private int renderedStarY = -1;
    private int renderedStarWidth = -1;

    // Visibility threshold for content rendering
    private static final float VISIBILITY_THRESHOLD = 0.2f;

    // Tooltip renderer
    private PatternTooltipRenderer tooltipRenderer;
    private PatternEntry hoveredEntry = null;

    public PatternListWidget(Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight, PatternManagerScreen parent) {
        super(minecraft, width, height, y0, y1, itemHeight);
        this.parent = parent;
        this.tooltipRenderer = new PatternTooltipRenderer();

    }

    public void updatePatterns(List<PatternStorage.SavedPattern> patterns) {
        this.patterns = new ArrayList<>(patterns);
        this.clearEntries();

        for (int i = 0; i < patterns.size(); i++) {
            this.addEntry(new PatternEntry(patterns.get(i), i, this));
        }

        // Don't reset scroll position - keep current position if valid
        double currentScroll = this.getScrollAmount();
        double maxScroll = this.getMaxScroll();

        if (currentScroll > maxScroll) {
            // Only adjust if current position is beyond new max
            this.setScrollAmount(maxScroll);
        }
        // Otherwise keep current scroll position

        // Reset velocity when patterns change
        smoothScroller.stopScroll();
    }

    public PatternStorage.SavedPattern getSelectedPattern() {
        PatternEntry selected = this.getSelected();
        return selected != null ? selected.pattern : null;
    }

    public List<PatternStorage.SavedPattern> getAllPatterns() {
        return new ArrayList<>(patterns);
    }


    public int getIndexAtPosition(double mouseY) {
        int relativeY = (int)(mouseY - this.y0);
        int scrollAdjustedY = relativeY + (int)this.getScrollAmount();

        // Account for top padding
        int rawIndex = (scrollAdjustedY - PATTERN_PADDING) / this.itemHeight;

        // Return -1 if clicking in the top padding area
        if (scrollAdjustedY < PATTERN_PADDING) {
            return -1;
        }

        // Get drag state
        int hiddenIndex = -1;
        int gapPosition = -1;
        if (parent.getDragDropManager() != null) {
            hiddenIndex = parent.getDragDropManager().getDraggedItemIndex();
            gapPosition = parent.getDragDropManager().getGapPosition();
        }

        // Adjust for hidden item and gap
        int adjustedIndex = rawIndex;
        if (hiddenIndex >= 0 && rawIndex >= hiddenIndex) {
            adjustedIndex++;
        }
        if (gapPosition >= 0 && rawIndex >= gapPosition) {
            adjustedIndex--;
        }

        if (adjustedIndex >= 0 && adjustedIndex < patterns.size()) {
            return adjustedIndex;
        }
        return -1;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.x0 + this.width - 6;
    }

    // remove extra 4 pixel padding from AbstractSelectionList.getMaxScroll
    @Override
    public int getMaxScroll() {
        return Math.max(0, this.getMaxPosition() - (this.y1 - this.y0));
    }

    @Override
    public int getRowWidth() {
        return this.width - 6;  // Reserve space for scrollbar
    }

    @Override
    protected void renderBackground(GuiGraphics guiGraphics) {
        // Override to prevent ANY default background rendering
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Update smooth scrolling
        double newScroll = smoothScroller.updateSmoothScroll(this.getScrollAmount(), this.getMaxScroll());
        if (Math.abs(newScroll - this.getScrollAmount()) > 0.01) {
            // Only update if there's actual movement, and use super to avoid stopping velocity
            super.setScrollAmount(newScroll);
        }

        // Custom render to avoid any default backgrounds
        this.renderList(guiGraphics, mouseX, mouseY, partialTick);
        this.renderDecorations(guiGraphics, mouseX, mouseY);

        // Render tooltips at the very end
        this.renderTooltips(guiGraphics, mouseX, mouseY);
    }

    private void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (hoveredEntry != null) {
            hoveredEntry.renderTooltipIfHovered(guiGraphics, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!this.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        smoothScroller.addScrollVelocity(delta, this.itemHeight);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.isMouseOver(mouseX, mouseY)) {
            int scrollbarX = this.getScrollbarPosition();
            if (mouseX >= scrollbarX && mouseX <= scrollbarX + 6) {
                double maxScroll = this.getMaxScroll();
                if (maxScroll > 0) {
                    double scrollRatio = deltaY / (this.y1 - this.y0);
                    double scrollDelta = scrollRatio * this.getMaxPosition();

                    // Direct position update for scrollbar dragging
                    double newPos = this.getScrollAmount() + scrollDelta;
                    newPos = Math.max(0, Math.min(newPos, maxScroll));
                    super.setScrollAmount(newPos);
                    smoothScroller.stopScroll();  // Stop velocity when manually dragging
                }
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public void setScrollAmount(double scroll) {
        smoothScroller.stopScroll();  // Stop any ongoing velocity
        super.setScrollAmount(scroll);
    }

    public boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        if (!this.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int scrollbarX = this.getScrollbarPosition();
        return mouseX >= scrollbarX && mouseX <= scrollbarX + 6;
    }

    private boolean isScrollbarVisible() {
        return this.getMaxScroll() > 0;
    }

    @Override
    protected void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(this.x0, this.y0, this.x0 + this.width, this.y1, 0x40000000);
        guiGraphics.enableScissor(this.x0, this.y0, this.x0 + this.width, this.y1);

        // Adjust for scrollbar presence
        int itemLeft;
        int itemWidth;

        //Scrollbar width
        int scrollbar_width = 6;

        if (isScrollbarVisible()) {
            itemLeft = this.x0 + PATTERN_PADDING;  // padding on left
            itemWidth = this.width - (scrollbar_width + PATTERN_PADDING * 2);  // Remove scrollbar + padding on both sides
        } else {
            itemLeft = this.x0 + PATTERN_PADDING;  // padding on left
            itemWidth = this.width - (PATTERN_PADDING * 2);  // padding on each side
        }

        int itemHeight = this.itemHeight;
        int itemCount = this.getItemCount();

        hoveredEntry = null;

        DragDropManager dragManager = parent.getDragDropManager();
        int hiddenIndex = -1;
        int gapPosition = dragManager != null ? dragManager.getGapPosition() : -1;

// Only hide the dragged item if we're in the same folder it was dragged from
        if (dragManager != null && dragManager.isDragging()) {
            String currentLocation = parent.getActualCurrentLocation();
            String sourceLocation = dragManager.getSourceLocation();

            if (currentLocation.equals(sourceLocation)) {
                hiddenIndex = dragManager.getDraggedItemIndex();
            }
        }

        int renderPosition = 0;

        for (int i = 0; i < itemCount; ++i) {
            // Check for gap BEFORE checking for hidden item (KEEP THIS CHANGE)
            if (gapPosition >= 0 && renderPosition == gapPosition) {
                renderPosition++;
            }

            // Skip the dragged item only if in same folder
            if (hiddenIndex >= 0 && i == hiddenIndex) {
                continue;
            }

            // Add padding to the first item position, which scrolls with the content
            int itemTop = this.y0 + PATTERN_PADDING + (renderPosition * this.itemHeight) - (int)this.getScrollAmount();
            int itemBottom = itemTop + itemHeight;

            int visibleTop = Math.max(itemTop, this.y0);
            int visibleBottom = Math.min(itemBottom, this.y1);
            int visibleHeight = visibleBottom - visibleTop;
            float visibilityPercentage = (float)visibleHeight / itemHeight;

            if (visibilityPercentage > 0.1f) {
                PatternEntry entry = this.getEntry(i);
                boolean isHovered = this.isMouseOver(mouseX, mouseY) &&
                        Objects.equals(this.getEntryAtPosition(mouseX, mouseY), entry);

                entry.render(guiGraphics, i, itemTop, itemLeft, itemWidth, itemHeight,
                        mouseX, mouseY, isHovered, partialTick);

                if (isHovered) {
                    hoveredEntry = entry;
                }
            }

            renderPosition++;
        }

        guiGraphics.disableScissor();
//        renderFadeOverlays(guiGraphics);
    }

    @Override
    protected int getMaxPosition() {
        // Include top and bottom padding in the total height calculation
        return this.getItemCount() * this.itemHeight + PATTERN_PADDING * 2;
    }

    private void renderFadeOverlays(GuiGraphics guiGraphics) {
        int fadeHeight = 20;

        // Top fade gradient
        for (int i = 0; i < fadeHeight; i++) {
            float alpha = (float)i / fadeHeight;
            int opacity = (int)(alpha * 0x40);
            int color = (opacity << 24) | 0x000000;
            guiGraphics.fill(this.x0, this.y0 + fadeHeight - i - 1,
                    this.x0 + this.width, this.y0 + fadeHeight - i, color);
        }

        // Bottom fade gradient
        for (int i = 0; i < fadeHeight; i++) {
            float alpha = (float)i / fadeHeight;
            int opacity = (int)(alpha * 0x40);
            int color = (opacity << 24) | 0x000000;
            guiGraphics.fill(this.x0, this.y1 - fadeHeight + i,
                    this.x0 + this.width, this.y1 - fadeHeight + i + 1, color);
        }
    }

    @Override
    protected void renderDecorations(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Custom scrollbar rendering
        int maxScroll = this.getMaxScroll();
        if (maxScroll > 0) {
            int scrollbarX = this.getScrollbarPosition();
            int scrollbarWidth = 6;

            int scrollbarHeight = (int)((float)((this.y1 - this.y0) * (this.y1 - this.y0)) / (float)this.getMaxPosition());
            scrollbarHeight = Mth.clamp(scrollbarHeight, 32, this.y1 - this.y0 - 8);
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

    public static class PatternEntry extends ObjectSelectionList.Entry<PatternEntry> {
        private final PatternStorage.SavedPattern pattern;
        private final int index;
        private final PatternListWidget listWidget;
        private long lastClickTime = 0;

        // Tooltip data
        private int tooltipX = -1;
        private int tooltipY = -1;
        private int tooltipTextWidth = 0;
        private List<HexPattern> tooltipPatterns = null;

        // Add these new fields to store actual rendered positions
        private int renderedStarX = -1;
        private int renderedStarY = -1;
        private int renderedStarWidth = -1;
        private int renderedStarHeight = -1;
        private int renderedDotsX = -1;
        private int renderedDotsY = -1;

        public PatternEntry(PatternStorage.SavedPattern pattern, int index, PatternListWidget listWidget) {
            this.pattern = pattern;
            this.index = index;
            this.listWidget = listWidget;
        }

        public static void clearAnimationState() {
            PatternAnimationManager.clearAllAnimations();
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {

            // Disable hover highlighting when dragging
            DragDropManager dragManager = listWidget.parent.getDragDropManager();
            if (dragManager != null && dragManager.isDragging()) {
                hovered = false;
            }

            // Calculate visible bounds
            int visibleTop = Math.max(y, listWidget.y0);
            int visibleBottom = Math.min(y + entryHeight, listWidget.y1);
            int visibleHeight = visibleBottom - visibleTop;

            // Calculate visibility percentage
            float visibilityPercentage = (float)visibleHeight / entryHeight;

            // Skip if barely visible
            if (visibilityPercentage < 0.1f) {
                // Reset stored positions when not visible
                this.renderedStarX = -1;
                this.renderedStarY = -1;
                this.renderedDotsX = -1;
                this.renderedDotsY = -1;
                return;
            }

            // Calculate fade alpha
            float fadeAlpha = calculateFadeAlpha(y, entryHeight);

            // Background
            PatternEntry selectedEntry = listWidget.getSelected();
            boolean isSelected = (selectedEntry != null && selectedEntry.index == this.index);

            if (isSelected) {
                if (hovered) {
                    int alpha = (int)(0x30 * fadeAlpha);
                    guiGraphics.fill(x, visibleTop, x + entryWidth, visibleBottom, (alpha << 24) | 0xFFFFFF);
                } else {
                    int alpha = (int)(0x18 * fadeAlpha);
                    guiGraphics.fill(x, visibleTop, x + entryWidth, visibleBottom, (alpha << 24) | 0xFFFFFF);
                }
            } else if (hovered) {
                int alpha = (int)(0x20 * fadeAlpha);
                guiGraphics.fill(x, visibleTop, x + entryWidth, visibleBottom, (alpha << 24) | 0xFFFFFF);
            }

            // Check if this pattern is being dragged
            boolean isBeingDragged = listWidget.parent.isDraggedFromHere(index);

            // Apply transparency if being dragged (placeholder effect)
            if (isBeingDragged) {
                fadeAlpha *= 0.2f; // Make it 20% opacity

                // Draw dashed border to indicate it's being dragged
                int dashLength = 5;
                int dashGap = 3;
                int dashColor = (int)(0x80 * fadeAlpha) << 24 | 0xFFFFFF;

                // Top dashed border
                for (int dx = x; dx < x + entryWidth; dx += dashLength + dashGap) {
                    int dashEnd = Math.min(dx + dashLength, x + entryWidth);
                    guiGraphics.fill(dx, visibleTop, dashEnd, visibleTop + 1, dashColor);
                    guiGraphics.fill(dx, visibleBottom - 1, dashEnd, visibleBottom, dashColor);
                }

                // Side dashed borders
                for (int dy = visibleTop; dy < visibleBottom; dy += dashLength + dashGap) {
                    int dashEnd = Math.min(dy + dashLength, visibleBottom);
                    guiGraphics.fill(x, dy, x + 1, dashEnd, dashColor);
                    guiGraphics.fill(x + entryWidth - 1, dy, x + entryWidth, dashEnd, dashColor);
                }
            }

            int centerY = y + entryHeight / 2;

            // Only render text if visible enough
            if (visibilityPercentage > VISIBILITY_THRESHOLD) {
                net.minecraft.client.gui.Font font = listWidget.minecraft.font;
                int fontHeight = font.lineHeight;

                // Calculate properly centered Y position for text
                int textY = y + (entryHeight - fontHeight) / 2;

                // Favorite star - vertically centered
                int starX = x + 5;
                int starColor = pattern.isFavorite() ? 0xFFFFD700 : 0xFF404040;
                if (fadeAlpha < 1.0f) {
                    int alpha = (int)(((starColor >> 24) & 0xFF) * fadeAlpha);
                    starColor = (alpha << 24) | (starColor & 0xFFFFFF);
                }
                guiGraphics.drawString(font, "★", starX, textY, starColor);

                // Store the actual rendered position for click detection
                this.renderedStarX = starX;
                this.renderedStarY = textY;
                this.renderedStarWidth = font.width("★");
                this.renderedStarHeight = font.lineHeight;

                // Pattern name - same Y position
                int textX = starX + 15;
                int textColor = 0xFFFFFF;
                if (fadeAlpha < 1.0f) {
                    int alpha = (int)(0xFF * fadeAlpha);
                    textColor = (alpha << 24) | 0xFFFFFF;
                }

                // Calculate pattern count text first
                int patternCount = pattern.getPatterns().size();
                String countText = " [" + patternCount + "]";
                int countTextWidth = font.width(countText);

                // Define the pattern area start position
                int patternAreaStartX = x + 200;

                // Calculate available width for name (from textX to pattern area start)
                int maxNameWidth = patternAreaStartX - textX - countTextWidth - 10; // Reserve space for count and padding
                String displayName = pattern.getName();

                if (font.width(displayName) > maxNameWidth) {
                    displayName = font.plainSubstrByWidth(displayName, maxNameWidth - font.width("...")) + "...";
                }
                guiGraphics.drawString(font, displayName, textX, textY, textColor);

                // Pattern count - positioned after the (possibly truncated) name
                int countX = textX + font.width(displayName); // Use displayName, not pattern.getName()
                int countColor = 0xFF808080;
                if (fadeAlpha < 1.0f) {
                    int alpha = (int)(0xFF * fadeAlpha);
                    countColor = (alpha << 24) | 0x808080;
                }
                guiGraphics.drawString(font, countText, countX, textY, countColor);

                // Three dots menu button with border
                renderThreeDotsButton(guiGraphics, x, y, entryWidth, entryHeight, mouseX, mouseY, fadeAlpha);
            } else {
                // Reset stored positions when not visible enough
                this.renderedStarX = -1;
                this.renderedStarY = -1;
                this.renderedDotsX = -1;
                this.renderedDotsY = -1;
            }

            // Hexagonal pattern grid
            int patternAreaX = x + 200;
            int dotsButtonStart = x + entryWidth - DOTS_BUTTON_SIZE - DOTS_BUTTON_OFFSET;
            int patternAreaWidth = dotsButtonStart - patternAreaX - 5; // 5px padding before dots

            int patternY = centerY - 15;
            int patternHeight = 30;

            // Only render patterns if visible
            if (patternY + patternHeight > listWidget.y0 && patternY < listWidget.y1) {
                renderHexPatternGrid(guiGraphics, pattern.getPatterns(), patternAreaX, patternY,
                        patternAreaWidth, patternHeight, fadeAlpha);
            }
        }

        private void renderThreeDotsButton(GuiGraphics guiGraphics, int x, int y, int entryWidth, int entryHeight, int mouseX, int mouseY, float fadeAlpha) {

            // --- Button Position ---
            int buttonX = x + entryWidth - DOTS_BUTTON_SIZE - DOTS_BUTTON_OFFSET;
            int buttonY = y + (entryHeight - DOTS_BUTTON_SIZE) / 2;

            // Store the actual rendered position for click detection
            this.renderedDotsX = buttonX;
            this.renderedDotsY = buttonY;

            // --- Button Rendering ---
            boolean buttonHovered = mouseX >= buttonX && mouseX < buttonX + DOTS_BUTTON_SIZE &&
                    mouseY >= buttonY && mouseY < buttonY + DOTS_BUTTON_SIZE;

            int alphaComponent = (int)(255 * fadeAlpha) << 24;

            int bgColor = buttonHovered ? 0x3A3A3A : 0x2A2A2A;
            int finalBgColor = (bgColor & 0x00FFFFFF) | alphaComponent;
            guiGraphics.fill(buttonX, buttonY, buttonX + DOTS_BUTTON_SIZE, buttonY + DOTS_BUTTON_SIZE, finalBgColor);

            // Simple border - only bright when hovered
            int borderColor = buttonHovered ? 0x606060 : 0x404040;
            int finalBorderColor = (borderColor & 0x00FFFFFF) | alphaComponent;
            guiGraphics.renderOutline(buttonX, buttonY, DOTS_BUTTON_SIZE, DOTS_BUTTON_SIZE, finalBorderColor);

            // --- Custom Pixel Dot Drawing ---
            // 1. Define offsets to manually adjust the dots' position inside the button.
            int dotsOffsetX = 0; // Positive moves right, negative moves left
            int dotsOffsetY = 0; // Positive moves down, negative moves up

            // 2. Define dot geometry
            int dotSize = 1;
            int dotSpacing = 1;

            // 3. Calculate visual dimensions
            int totalDotsWidth = (3 * dotSize) + (2 * dotSpacing);
            int totalVisualWidth = totalDotsWidth + 1;
            int totalVisualHeight = dotSize + 1;

            // 4. Calculate the centered position, then apply the manual offsets
            int startX = buttonX + (DOTS_BUTTON_SIZE - totalVisualWidth) / 2 + dotsOffsetX;
            int startY = buttonY + (DOTS_BUTTON_SIZE - totalVisualHeight) / 2 + dotsOffsetY;

            // 5. Determine colors
            int dotsColor = buttonHovered ? 0xFFFFFF : 0xCCCCCC;
            int finalDotsColor = (dotsColor & 0x00FFFFFF) | alphaComponent;
            int shadowColor = 0x1A1A1A;
            int finalShadowColor = (shadowColor & 0x00FFFFFF) | alphaComponent;

            // 6. Draw the dots
            for (int i = 0; i < 3; i++) {
                int currentDotX = startX + i * (dotSize + dotSpacing);
                guiGraphics.fill(currentDotX + 1, startY + 1, currentDotX + dotSize + 1, startY + dotSize + 1, finalShadowColor);
                guiGraphics.fill(currentDotX, startY, currentDotX + dotSize, startY + dotSize, finalDotsColor);
            }
        }

        private float calculateFadeAlpha(int y, int height) {
            int fadeZone = 20;
            int itemCenter = y + height / 2;

            int distanceFromTop = itemCenter - listWidget.y0;
            int distanceFromBottom = listWidget.y1 - itemCenter;

            float alpha = 1.0f;

            if (distanceFromTop < fadeZone) {
                alpha = Math.max(0.0f, (float)distanceFromTop / fadeZone);
            } else if (distanceFromBottom < fadeZone) {
                alpha = Math.max(0.0f, (float)distanceFromBottom / fadeZone);
            }

            return alpha;
        }

        private void renderHexPatternGrid(GuiGraphics guiGraphics, List<HexPattern> patterns,
                                          int x, int y, int maxWidth, int height, float fadeAlpha) {
            if (patterns.isEmpty()) return;

            // Calculate actual widths of all patterns
            List<PatternRenderer.PatternDimensions> dimensions = new ArrayList<>();
            for (HexPattern pattern : patterns) {
                dimensions.add(PatternRenderer.calculateDimensions(pattern, height));
            }

            // Determine how many patterns we can actually show
            int patternsToRender = 0;
            int currentWidth = 0;
            int spacing = 2; // GridLayout spacing

            // First, try to fit all patterns (up to 12)
            for (int i = 0; i < Math.min(patterns.size(), 12); i++) {
                int neededWidth = dimensions.get(i).width;
                if (i > 0) neededWidth += spacing;

                if (currentWidth + neededWidth <= maxWidth) {
                    currentWidth += neededWidth;
                    patternsToRender++;
                } else {
                    break;
                }
            }

            // If we can't render all patterns, recalculate with space for "+N" text
            if (patternsToRender < patterns.size()) {
                String overflowText = "+" + (patterns.size() - patternsToRender);
                int textWidth = listWidget.minecraft.font.width(overflowText) + 10;
                int availableWidth = maxWidth - textWidth;

                // Recalculate how many patterns fit in reduced space
                patternsToRender = 0;
                currentWidth = 0;
                for (int i = 0; i < Math.min(patterns.size(), 12); i++) {
                    int neededWidth = dimensions.get(i).width;
                    if (i > 0) neededWidth += spacing;

                    if (currentWidth + neededWidth <= availableWidth) {
                        currentWidth += neededWidth;
                        patternsToRender++;
                    } else {
                        break;
                    }
                }
            }

            // Now render only the patterns that fit
            List<HexPattern> patternsToShow = patterns.subList(0, patternsToRender);

            PatternRenderer.GridLayout layout = new PatternRenderer.GridLayout(12, spacing, PatternRenderer.RenderStyle.ANIMATED);
            PatternRenderer.RenderOptions options = new PatternRenderer.RenderOptions()
                    .withFadeAlpha(fadeAlpha)
                    .withAnimationKey(pattern.getUuid());

            PatternRenderer.renderPatternGrid(guiGraphics, patternsToShow, x, y, maxWidth, height, layout, options);

            // Render "+N" if needed
            if (patternsToRender < patterns.size()) {
                List<HexPattern> remaining = patterns.subList(patternsToRender, patterns.size());
                String moreText = "+" + remaining.size();

                int textX = x + layout.finalWidth - spacing + 5;
                int textY = y + (height - listWidget.minecraft.font.lineHeight) / 2;

                int textColor = 0xFF808080;
                if (fadeAlpha < 1.0f) {
                    int alpha = (int)(0xFF * fadeAlpha);
                    textColor = (alpha << 24) | 0x808080;
                }
                guiGraphics.drawString(listWidget.minecraft.font, moreText, textX, textY, textColor);

                this.tooltipX = textX;
                this.tooltipY = textY;
                this.tooltipTextWidth = listWidget.minecraft.font.width(moreText);
                this.tooltipPatterns = remaining;
            } else {
                this.tooltipPatterns = null;
            }
        }

        public void renderTooltipIfHovered(GuiGraphics guiGraphics, int mouseX, int mouseY) {
            if (tooltipPatterns != null && tooltipX != -1) {
                if (mouseX >= tooltipX && mouseX <= tooltipX + tooltipTextWidth &&
                        mouseY >= tooltipY && mouseY <= tooltipY + 10) {
                    listWidget.tooltipRenderer.renderTooltip(guiGraphics, tooltipPatterns, mouseX, mouseY);
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            long currentTime = System.currentTimeMillis();

            if (button == 0) {
                listWidget.setSelected(this);
                listWidget.parent.onPatternSelected(index);

                lastClickTime = currentTime;

                // Check star click using actual rendered position
                if (renderedStarX != -1 &&
                        mouseX >= renderedStarX && mouseX <= renderedStarX + renderedStarWidth &&
                        mouseY >= renderedStarY && mouseY <= renderedStarY + renderedStarHeight) {
                    listWidget.parent.toggleFavorite(pattern);
                    return true;
                }

                // Check dots menu click using stored position
                if (renderedDotsX != -1 &&
                        mouseX >= renderedDotsX && mouseX < renderedDotsX + DOTS_BUTTON_SIZE &&
                        mouseY >= renderedDotsY && mouseY < renderedDotsY + DOTS_BUTTON_SIZE) {
                    listWidget.parent.onPatternRightClicked(index, (int)mouseX, (int)mouseY);
                    return true;
                }

            } else if (button == 1) {
                // Right click anywhere on the entry
                listWidget.setSelected(this);
                listWidget.parent.onPatternRightClicked(index, (int)mouseX, (int)mouseY);
                return true;
            }

            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public Component getNarration() {
            return Component.literal(pattern.getName() + " (" + pattern.getPatterns().size() + " patterns)");
        }
    }
}