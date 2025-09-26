package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Handles rendering of pattern tooltips with responsive layout.
 * Now uses PatternRenderer for actual pattern drawing.
 */
public class PatternTooltipRenderer {
    private final Minecraft minecraft;

    // Tooltip configuration
    private static final int TOOLTIP_PATTERN_SIZE = 15;
    private static final int TOOLTIP_PADDING = 4;
    private static final int PATTERN_SPACING = 2;
    private static final int MAX_PATTERNS_PER_ROW = 10;
    private static final int MAX_ROWS = 5;
    private static final int SCREEN_MARGIN = 5;

    public PatternTooltipRenderer() {
        this.minecraft = Minecraft.getInstance();
    }

    /**
     * Render a tooltip with patterns at the specified position
     */
    public void renderTooltip(GuiGraphics guiGraphics, List<HexPattern> patterns, int mouseX, int mouseY) {
        if (patterns.isEmpty()) return;

        // Push the pose to ensure tooltip renders on top
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 400);

        // Calculate available screen space
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();

        // Calculate optimal layout
        TooltipLayout layout = calculateOptimalLayout(patterns, screenWidth);

        // Position the tooltip
        TooltipPosition position = calculateTooltipPosition(
                layout.width, layout.height, mouseX, mouseY, screenWidth, screenHeight
        );

        // Draw tooltip background
        drawTooltipBackground(guiGraphics, position.x, position.y, layout.width, layout.height);

        // Render patterns using the new renderer
        renderPatternsInTooltip(guiGraphics, patterns, position.x, position.y, layout);

        // Draw "and X more..." text if needed
        if (layout.hasMore) {
            drawMoreText(guiGraphics, patterns.size() - layout.patternsToShow,
                    position.x, position.y, layout.height);
        }

        guiGraphics.pose().popPose();
    }

    private TooltipLayout calculateOptimalLayout(List<HexPattern> patterns, int screenWidth) {
        int maxTooltipWidth = screenWidth - (2 * SCREEN_MARGIN);
        int totalPatterns = patterns.size();
        int patternsPerRow = MAX_PATTERNS_PER_ROW;
        int rows;
        int patternsToShow;

        // Calculate how many patterns we can show
        while (patternsPerRow > 1) {
            int maxPatternsToShow = Math.min(totalPatterns, patternsPerRow * MAX_ROWS);
            patternsToShow = Math.min(totalPatterns, maxPatternsToShow);
            rows = (patternsToShow + patternsPerRow - 1) / patternsPerRow;

            // Calculate width needed
            int maxRowWidth = calculateMaxRowWidth(patterns, patternsToShow, patternsPerRow, rows);

            if (totalPatterns > maxPatternsToShow) {
                String moreText = "and " + (totalPatterns - maxPatternsToShow) + " more...";
                int textWidth = minecraft.font.width(moreText);
                maxRowWidth = Math.max(maxRowWidth, textWidth + 10);
            }

            int actualTooltipWidth = maxRowWidth + TOOLTIP_PADDING * 2;

            if (actualTooltipWidth <= maxTooltipWidth) {
                break;
            }

            patternsPerRow--;
        }

        if (patternsPerRow < 1) {
            patternsPerRow = 1;
        }

        // Final layout calculation
        int maxPatternsToShow = Math.min(totalPatterns, patternsPerRow * MAX_ROWS);
        patternsToShow = Math.min(totalPatterns, maxPatternsToShow);
        boolean hasMore = totalPatterns > maxPatternsToShow;
        rows = (patternsToShow + patternsPerRow - 1) / patternsPerRow;

        int maxRowWidth = calculateMaxRowWidth(patterns, patternsToShow, patternsPerRow, rows);
        if (hasMore) {
            String moreText = "and " + (totalPatterns - maxPatternsToShow) + " more...";
            int textWidth = minecraft.font.width(moreText);
            maxRowWidth = Math.max(maxRowWidth, textWidth + 10);
        }

        int width = Math.min(maxRowWidth + TOOLTIP_PADDING * 2, maxTooltipWidth);
        int height = rows * (TOOLTIP_PATTERN_SIZE + PATTERN_SPACING) - PATTERN_SPACING + TOOLTIP_PADDING * 2;
        if (hasMore) {
            height += 12;
        }

        return new TooltipLayout(width, height, patternsPerRow, rows, patternsToShow, hasMore);
    }

    private int calculateMaxRowWidth(List<HexPattern> patterns, int patternsToShow,
                                     int patternsPerRow, int rows) {
        int maxRowWidth = 0;
        for (int row = 0; row < rows; row++) {
            int rowWidth = 0;
            int start = row * patternsPerRow;
            int end = Math.min(start + patternsPerRow, patternsToShow);

            for (int i = start; i < end && i < patterns.size(); i++) {
                PatternRenderer.PatternDimensions dims = PatternRenderer.calculateDimensions(
                        patterns.get(i), TOOLTIP_PATTERN_SIZE
                );
                rowWidth += dims.width;
                if (i < end - 1) {
                    rowWidth += PATTERN_SPACING;
                }
            }
            maxRowWidth = Math.max(maxRowWidth, rowWidth);
        }
        return maxRowWidth;
    }

    private TooltipPosition calculateTooltipPosition(int tooltipWidth, int tooltipHeight,
                                                     int mouseX, int mouseY,
                                                     int screenWidth, int screenHeight) {
        int tooltipX, tooltipY;

        // Check available space
        int spaceAbove = mouseY - SCREEN_MARGIN;
        int spaceBelow = screenHeight - mouseY - SCREEN_MARGIN;
        int spaceRight = screenWidth - mouseX - SCREEN_MARGIN;
        int spaceLeft = mouseX - SCREEN_MARGIN;

        // Vertical positioning
        if (spaceAbove > tooltipHeight + 12) {
            tooltipY = mouseY - tooltipHeight - 12;
        } else if (spaceBelow > tooltipHeight + 12) {
            tooltipY = mouseY + 12;
        } else {
            tooltipY = Math.max(SCREEN_MARGIN, (screenHeight - tooltipHeight) / 2);
        }

        // Horizontal positioning
        if (spaceRight > tooltipWidth + 12) {
            tooltipX = mouseX + 12;
        } else if (spaceLeft > tooltipWidth + 12) {
            tooltipX = mouseX - tooltipWidth - 12;
        } else {
            tooltipX = Math.max(SCREEN_MARGIN, (screenWidth - tooltipWidth) / 2);
        }

        // Final bounds check
        tooltipX = Math.max(SCREEN_MARGIN, Math.min(tooltipX, screenWidth - tooltipWidth - SCREEN_MARGIN));
        tooltipY = Math.max(SCREEN_MARGIN, Math.min(tooltipY, screenHeight - tooltipHeight - SCREEN_MARGIN));

        return new TooltipPosition(tooltipX, tooltipY);
    }

    private void drawTooltipBackground(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        int bgColor = 0xF0000000;
        int borderLight = 0xFF707070;
        int borderDark = 0xFF404040;

        // Main background
        guiGraphics.fill(x, y, x + width, y + height, bgColor);

        // Beveled edges
        guiGraphics.fill(x, y, x + width - 1, y + 1, borderLight);
        guiGraphics.fill(x, y, x + 1, y + height - 1, borderLight);
        guiGraphics.fill(x + 1, y + height - 1, x + width, y + height, borderDark);
        guiGraphics.fill(x + width - 1, y + 1, x + width, y + height, borderDark);
    }

    private void renderPatternsInTooltip(GuiGraphics guiGraphics, List<HexPattern> patterns,
                                         int tooltipX, int tooltipY, TooltipLayout layout) {
        int startX = tooltipX + TOOLTIP_PADDING;
        int startY = tooltipY + TOOLTIP_PADDING;

        PatternRenderer.RenderOptions options = new PatternRenderer.RenderOptions()
                .withFadeAlpha(0.8f); // Tooltips are slightly transparent

        for (int i = 0; i < layout.patternsToShow && i < patterns.size(); i++) {
            HexPattern pattern = patterns.get(i);

            int row = i / layout.patternsPerRow;
            int col = i % layout.patternsPerRow;

            // Calculate X position
            int px = startX;
            for (int j = 0; j < col; j++) {
                int idx = row * layout.patternsPerRow + j;
                if (idx < patterns.size()) {
                    PatternRenderer.PatternDimensions dims = PatternRenderer.calculateDimensions(
                            patterns.get(idx), TOOLTIP_PATTERN_SIZE
                    );
                    px += dims.width + PATTERN_SPACING;
                }
            }

            // Calculate Y position
            int py = startY + row * (TOOLTIP_PATTERN_SIZE + PATTERN_SPACING);

            // Get dimensions for this pattern
            PatternRenderer.PatternDimensions dims = PatternRenderer.calculateDimensions(
                    pattern, TOOLTIP_PATTERN_SIZE
            );

            // Render using PatternRenderer
            PatternRenderer.renderPattern(
                    guiGraphics, pattern, px, py, dims.width, TOOLTIP_PATTERN_SIZE,
                    PatternRenderer.RenderStyle.TOOLTIP, options
            );
        }
    }

    private void drawMoreText(GuiGraphics guiGraphics, int remainingCount, int x, int y, int height) {
        String moreText = "and " + remainingCount + " more...";
        int textX = x + TOOLTIP_PADDING;
        int textY = y + height - TOOLTIP_PADDING - 8;
        guiGraphics.drawString(minecraft.font, moreText, textX, textY, 0xFFAAAAAA);
    }

    // Container classes
    private static class TooltipLayout {
        final int width;
        final int height;
        final int patternsPerRow;
        final int rows;
        final int patternsToShow;
        final boolean hasMore;

        TooltipLayout(int width, int height, int patternsPerRow, int rows,
                      int patternsToShow, boolean hasMore) {
            this.width = width;
            this.height = height;
            this.patternsPerRow = patternsPerRow;
            this.rows = rows;
            this.patternsToShow = patternsToShow;
            this.hasMore = hasMore;
        }
    }

    private static class TooltipPosition {
        final int x;
        final int y;

        TooltipPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}