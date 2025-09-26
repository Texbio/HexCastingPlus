package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.eval.SpecialPatterns;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class PatternEditScreen extends Screen {
    private final PatternManagerScreen parentScreen;
    private final PatternStorage.SavedPattern originalPattern;
    private final String sourceFolder;

    // Layout constants
    public static final int PATTERN_SIZE = 40;
    public static final int PATTERN_SPACING = 5;
    public static final int DISPOSAL_AREA_HEIGHT = PATTERN_SIZE + 20;
    private static final int EDIT_AREA_PADDING = 10;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_MARGIN = 10;

    // Scroll handling with smooth scrolling
    private double scrollAmount = 0;
    private double maxScroll = 0;
    private boolean scrolling = false;
    private final SmoothScrollHelper smoothScroller = new SmoothScrollHelper();

    // Tooltip handling - using Shift key
    private int hoveredPatternIndex = -1;
    private boolean hoveredInDisposal = false;

    //Temp
    private static final int DISPOSAL_PATTERNS_Y = 35;

    // Track patterns with their original line content
    static class EditablePattern {
        HexPattern pattern;
        String originalLine;
        String patternName;
        private String displayText;

        EditablePattern(HexPattern pattern, String originalLine) {
            this.pattern = pattern;
            this.originalLine = originalLine;

            String cleanLine = originalLine;
            if (cleanLine.contains("//")) {
                cleanLine = cleanLine.substring(0, cleanLine.indexOf("//"));
            }
            cleanLine = cleanLine.trim();

            this.patternName = (!cleanLine.isEmpty()) ? cleanLine : "Unknown";

            // ADD THIS BLOCK
            // Extract just the number for display if it's a Numerical Reflection
            if (patternName.startsWith("Numerical Reflection:")) {
                this.displayText = patternName.substring("Numerical Reflection:".length()).trim();
            } else {
                this.displayText = null; // Will render as pattern
            }
        }
        public String getDisplayText() {
            return displayText;
        }
    }

    private List<EditablePattern> editingPatterns;
    private List<EditablePattern> disposalPatterns = new ArrayList<>();
    private List<PatternStorage.PatternLine> allOriginalLines;

    private PatternEditDragManager dragManager;
    private Button saveButton;
    private Button cancelButton;

    // // ============= BACKGROUND

    private static final Random BACKGROUND_RANDOM = new Random(42); // Fixed seed for consistency
    private static List<BackgroundPattern> backgroundPatterns = null;
    private static int lastScreenWidth = 0;
    private static int lastScreenHeight = 0;

    private static class Rectangle {
        final float x, y, width, height;

        Rectangle(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean intersects(Rectangle other) {
            return x < other.x + other.width &&
                    x + width > other.x &&
                    y < other.y + other.height &&
                    y + height > other.y;
        }
    }

    private static class BackgroundPattern {
        final HexPattern pattern;
        final float x, y;
        final float scale;
        final float rotation;
        final float alpha;
        final float squeezeX, squeezeY;
        final int color;  // Add color field

        BackgroundPattern(HexPattern pattern, float x, float y, float scale, float rotation, float alpha, float squeezeX, float squeezeY, int color) {
            this.pattern = pattern;
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.rotation = rotation;
            this.alpha = alpha;
            this.squeezeX = squeezeX;
            this.squeezeY = squeezeY;
            this.color = color;
        }
    }

    private void initializeBackgroundPatterns() {
        // Only generate patterns once
        if (backgroundPatterns != null) {
            return;
        }

        backgroundPatterns = new ArrayList<>();

        // Get patterns from the registry
        List<HexPattern> availablePatterns = new ArrayList<>();
        try {
            var registry = IXplatAbstractions.INSTANCE.getActionRegistry();

            for (var entry : registry.entrySet()) {
                ActionRegistryEntry actionEntry = entry.getValue();
                if (actionEntry != null && actionEntry.prototype() != null) {
                    HexPattern pattern = actionEntry.prototype();
                    if (pattern.anglesSignature().length() < 10) {
                        availablePatterns.add(pattern);
                    }
                }
            }

            availablePatterns.add(SpecialPatterns.INTROSPECTION);
            availablePatterns.add(SpecialPatterns.RETROSPECTION);
            availablePatterns.add(SpecialPatterns.CONSIDERATION);

        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[PatternEditScreen] ERROR: Failed to load patterns from registry for background");
                System.out.println("  Exception: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        if (availablePatterns.isEmpty()) return;

        Collections.shuffle(availablePatterns, BACKGROUND_RANDOM);

        int basePatternCount = 30;
        float screenArea = (this.width * this.height) / (800f * 600f); // Normalize to typical screen
        int patternCount = Math.max(10, Math.min(30, (int)(basePatternCount * Math.sqrt(screenArea))));

        for (int i = 0; i < patternCount && i < availablePatterns.size(); i++) {
            HexPattern pattern = availablePatterns.get(i);

            // Distribute patterns more evenly using a grid-based approach with randomization
            int cols = (int)Math.ceil(Math.sqrt(patternCount * ((float)this.width / this.height)));
            int rows = (int)Math.ceil((float)patternCount / cols);

            int col = i % cols;
            int row = i / cols;

            // Base position on grid, then add random offset
            float gridX = (float)col / cols;
            float gridY = (float)row / rows;

            // Add random offset within grid cell
            float offsetX = (BACKGROUND_RANDOM.nextFloat() - 0.5f) * (1.0f / cols) * 1.5f;
            float offsetY = (BACKGROUND_RANDOM.nextFloat() - 0.5f) * (1.0f / rows) * 1.5f;

            float x = Math.max(0, Math.min(1, gridX + offsetX));
            float y = Math.max(0, Math.min(1, gridY + offsetY));

            float scale = 3.0f + BACKGROUND_RANDOM.nextFloat() * 4.0f;
            float rotation = BACKGROUND_RANDOM.nextFloat() * 360f;
            float alpha = 0.03f + BACKGROUND_RANDOM.nextFloat() * 0.1f;
            float squeezeX = 0.7f + BACKGROUND_RANDOM.nextFloat() * 0.3f;
            float squeezeY = 0.7f + BACKGROUND_RANDOM.nextFloat() * 0.3f;

            // Generate muted random color
            float hue = BACKGROUND_RANDOM.nextFloat() * 360f;
            float saturation = 0.2f + BACKGROUND_RANDOM.nextFloat() * 0.3f;
            float brightness = 0.3f + BACKGROUND_RANDOM.nextFloat() * 0.3f;
            int color = java.awt.Color.HSBtoRGB(hue / 360f, saturation, brightness);

            backgroundPatterns.add(new BackgroundPattern(pattern, x, y, scale, rotation, alpha, squeezeX, squeezeY, color));
        }
    }

    private void renderBackgroundPatterns(GuiGraphics guiGraphics) {
        if (backgroundPatterns == null || backgroundPatterns.isEmpty()) return;

        // Scale base size with screen dimensions (use smaller dimension to ensure patterns fit)
        int baseSize = Math.min(this.width, this.height) / 8;

        for (BackgroundPattern bp : backgroundPatterns) {
            guiGraphics.pose().pushPose();

            float actualX = bp.x * this.width;
            float actualY = bp.y * this.height;

            guiGraphics.pose().translate(actualX, actualY, 0);
            guiGraphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(bp.rotation));
            guiGraphics.pose().scale(bp.scale * bp.squeezeX, bp.scale * bp.squeezeY, 1.0f);

            // Set color tint using RenderSystem
            float r = ((bp.color >> 16) & 0xFF) / 255.0f;
            float g = ((bp.color >> 8) & 0xFF) / 255.0f;
            float b = (bp.color & 0xFF) / 255.0f;
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, bp.alpha);

            // Use scaled size instead of fixed 60
            PatternRenderer.renderPattern(
                    guiGraphics,
                    bp.pattern,
                    -baseSize/2,
                    -baseSize/2,
                    baseSize,
                    baseSize,
                    PatternRenderer.RenderStyle.STATIC,
                    new PatternRenderer.RenderOptions().withFadeAlpha(1.0f),
                    false
            );

            // Reset color
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

            guiGraphics.pose().popPose();
        }
    }

    // =============

    public PatternEditScreen(PatternManagerScreen parentScreen, PatternStorage.SavedPattern pattern, String folder) {
        super(Component.literal("Edit Pattern: " + pattern.getName()));
        this.parentScreen = parentScreen;
        this.originalPattern = pattern;
        this.sourceFolder = folder;

        this.editingPatterns = new ArrayList<>();
        this.allOriginalLines = PatternStorage.loadPatternFileWithComments(pattern.getName(), folder);

        for (PatternStorage.PatternLine line : allOriginalLines) {
            if (line.isPattern()) {
                this.editingPatterns.add(new EditablePattern(
                        line.getPattern(),
                        line.getRawLine()
                ));
            }
        }

        this.dragManager = new PatternEditDragManager(this);
    }

    @Override
    protected void init() {
        super.init();

        initializeBackgroundPatterns();

        this.saveButton = new SmallButton(
                this.width / 2 - 65,
                this.height - 30,
                60,
                20,
                Component.literal("Save"),
                button -> saveAndExit()
        );

        this.cancelButton = new SmallButton(
                this.width / 2 + 5,
                this.height - 30,
                60,
                20,
                Component.literal("Cancel"),
                button -> this.onClose()
        );

        this.addRenderableWidget(saveButton);
        this.addRenderableWidget(cancelButton);

        updateMaxScroll();
    }

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

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);

        // Update smooth scrolling
        double newScroll = smoothScroller.updateSmoothScroll(this.scrollAmount, this.maxScroll);
        if (Math.abs(newScroll - this.scrollAmount) > 0.01) {
            this.scrollAmount = newScroll;
        }

        // Update tooltip hover tracking
        updateTooltipHover(mouseX, mouseY);

        // Title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 5, 0xFFFFFF);

        // Disposal area background (top)
        // Disposal area background (top)
        guiGraphics.fillGradient(10, 20, this.width - 10, 20 + DISPOSAL_AREA_HEIGHT, 0xC0101010, 0xD0101010);

// Main edit area background (middle)
        int editAreaY = 30 + DISPOSAL_AREA_HEIGHT;
        int editAreaBottom = this.height - 40;
        guiGraphics.fillGradient(10, editAreaY, this.width - 10, editAreaBottom, 0xC0101010, 0xD0101010);

        // Render background patterns ONLY within the gray areas using scissor
        // For disposal area
        guiGraphics.enableScissor(11, 21, this.width - 11, 19 + DISPOSAL_AREA_HEIGHT);
        renderBackgroundPatterns(guiGraphics);
        guiGraphics.disableScissor();

        // For edit area
        guiGraphics.enableScissor(11, editAreaY + 1, this.width - 11, editAreaBottom - 1);
        renderBackgroundPatterns(guiGraphics);
        guiGraphics.disableScissor();

        // Now render the actual pattern boxes on top
        guiGraphics.enableScissor(11, 21, this.width - 11, 19 + DISPOSAL_AREA_HEIGHT);
        renderDisposalArea(guiGraphics, mouseX, mouseY);
        guiGraphics.disableScissor();

        // Enable scissor for edit area to prevent clipping
        guiGraphics.enableScissor(11, editAreaY + 1, this.width - 11, editAreaBottom - 1);
        renderEditArea(guiGraphics, mouseX, mouseY, editAreaY);
        guiGraphics.disableScissor();

        // Render scrollbar if needed
        renderScrollbar(guiGraphics, mouseX, mouseY, editAreaY, editAreaBottom);

        // Drag overlay
        dragManager.render(guiGraphics, mouseX, mouseY, partialTick);

        // Render tooltip last (on top of everything) - OUTSIDE of scissor regions
        renderTooltip(guiGraphics, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderDisposalArea(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int scrollbarSpace = (maxScroll > 0) ? (SCROLLBAR_WIDTH + SCROLLBAR_MARGIN) : 0;
        int availableWidth = this.width - 30 - scrollbarSpace;
        int patternsPerRow = Math.max(1, availableWidth / (PATTERN_SIZE + PATTERN_SPACING));

        // Calculate max patterns that fit in disposal area
        int maxRows = (DISPOSAL_AREA_HEIGHT - 15) / (PATTERN_SIZE + PATTERN_SPACING);  // 15 for padding
        int maxPatternsVisible = patternsPerRow * maxRows;

        // Check if we have overflow
        boolean hasOverflow = disposalPatterns.size() > maxPatternsVisible;
        int patternsToShow = hasOverflow ? maxPatternsVisible - 1 : disposalPatterns.size();  // Reserve space for "..."

        // Calculate the width of the container (based on max patterns per row)
        int containerWidth = patternsPerRow * PATTERN_SIZE + (patternsPerRow - 1) * PATTERN_SPACING;

        // Center the container
        int containerX = 11 + (availableWidth - containerWidth) / 2;

        int x = containerX;
        int y = DISPOSAL_PATTERNS_Y;
        int visualIndex = 0;

        // Adjust insertion index when dragging within same area
        int insertionIndex = dragManager.getInsertionIndex();

        for (int i = 0; i < Math.min(disposalPatterns.size(), patternsToShow); i++) {
            // Skip rendering the dragged item
            if (dragManager.isDraggingFromDisposal() && i == dragManager.getDraggedIndex()) {
                continue;
            }

            // Calculate position for this index
            int row = visualIndex / patternsPerRow;
            int col = visualIndex % patternsPerRow;
            x = containerX + col * (PATTERN_SIZE + PATTERN_SPACING);
            y = 35 + row * (PATTERN_SIZE + PATTERN_SPACING);  // Updated Y start

            // Show insertion gap where mouse is hovering
            if (dragManager.isDraggingToDisposal() && visualIndex == insertionIndex) {
                // Just shift position for the gap
                col++;
                if (col >= patternsPerRow) {
                    col = 0;
                    row++;
                }
                x = containerX + col * (PATTERN_SIZE + PATTERN_SPACING);
                y = 35 + row * (PATTERN_SIZE + PATTERN_SPACING);  // Updated Y start
                visualIndex++;
            }

            EditablePattern ep = disposalPatterns.get(i);

            boolean hovered = mouseX >= x && mouseX < x + PATTERN_SIZE &&
                    mouseY >= y && mouseY < y + PATTERN_SIZE;

            int bgColor = hovered ? 0xFF3A3A3A : 0xFF2A2A2A;
            guiGraphics.fill(x, y, x + PATTERN_SIZE, y + PATTERN_SIZE, bgColor);

            if (ep.displayText != null) {
                renderNumber(guiGraphics, ep.displayText, x, y, PATTERN_SIZE);
            } else {
                PatternRenderer.renderPattern(guiGraphics, ep.pattern, x, y, PATTERN_SIZE, PATTERN_SIZE,
                        PatternRenderer.RenderStyle.STATIC, new PatternRenderer.RenderOptions(), true);
            }

            visualIndex++;
        }

        // ADD THIS: Render "..." if there's overflow
        if (hasOverflow) {
            int row = visualIndex / patternsPerRow;
            int col = visualIndex % patternsPerRow;
            x = containerX + col * (PATTERN_SIZE + PATTERN_SPACING);
            y = 35 + row * (PATTERN_SIZE + PATTERN_SPACING);

            // Draw a box with "..." in it
            guiGraphics.fill(x, y, x + PATTERN_SIZE, y + PATTERN_SIZE, 0xFF1A1A1A);

            // Center the "..." text
            String ellipsis = "...";
            int textWidth = this.font.width(ellipsis);
            int textX = x + (PATTERN_SIZE - textWidth) / 2;
            int textY = y + (PATTERN_SIZE - this.font.lineHeight) / 2;
            guiGraphics.drawString(this.font, ellipsis, textX, textY, 0x808080);
        }

        // Handle insertion at the end
        if (dragManager.isDraggingToDisposal() && insertionIndex == visualIndex) {
            // Gap at the end (no visual needed, just for completeness)
        }
    }

    private void renderNumber(GuiGraphics guiGraphics, String number, int x, int y, int size) {
        // Add "N:" prefix to the number
        String displayText = "N:" + number;

        // Calculate text dimensions
        int textWidth = this.font.width(displayText);
        int textHeight = this.font.lineHeight;

        // Calculate scale to fit within the box (with padding)
        int maxWidth = size - 4;  // 2 pixel padding on each side
        float scale = 1.0f;

        if (textWidth > maxWidth) {
            scale = (float)maxWidth / textWidth;
            // Limit minimum scale for readability
            scale = Math.max(scale, 0.5f);
        }

        // Center the text in the box
        float centerX = x + size / 2.0f;
        float centerY = y + size / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX, centerY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        // Draw the number centered
        int drawX = -textWidth / 2;
        int drawY = -textHeight / 2;

        // Use a color that stands out
        int textColor = 0xFFFFFF;  // White
        guiGraphics.drawString(this.font, displayText, drawX, drawY, textColor);

        guiGraphics.pose().popPose();
    }

    private void renderEditArea(GuiGraphics guiGraphics, int mouseX, int mouseY, int areaY) {
        int scrollbarSpace = (maxScroll > 0) ? (SCROLLBAR_WIDTH + SCROLLBAR_MARGIN) : 0;
        int availableWidth = this.width - 30 - scrollbarSpace;
        int patternsPerRow = Math.max(1, availableWidth / (PATTERN_SIZE + PATTERN_SPACING));

        // Calculate the width of the container
        int containerWidth = patternsPerRow * PATTERN_SIZE + (patternsPerRow - 1) * PATTERN_SPACING;

        // Center the container
        int containerX = 11 + (availableWidth - containerWidth) / 2;

        int x = containerX;
        int y = areaY + EDIT_AREA_PADDING - (int)scrollAmount;
        int visualIndex = 0;

        // Use the insertion index directly - drag manager already handles adjustments
        int insertionIndex = dragManager.getInsertionIndex();

        for (int i = 0; i < editingPatterns.size(); i++) {
            // Skip rendering the dragged item
            if (dragManager.isDraggingFromEdit() && i == dragManager.getDraggedIndex()) {
                continue;
            }

            // Calculate position for this index
            int row = visualIndex / patternsPerRow;
            int col = visualIndex % patternsPerRow;
            x = containerX + col * (PATTERN_SIZE + PATTERN_SPACING);
            y = areaY + EDIT_AREA_PADDING - (int)scrollAmount + row * (PATTERN_SIZE + PATTERN_SPACING);

            // Show insertion gap where mouse is hovering
            if (dragManager.isDraggingToEdit() && visualIndex == insertionIndex) {
                // Just shift position for the gap
                col++;
                if (col >= patternsPerRow) {
                    col = 0;
                    row++;
                }
                x = containerX + col * (PATTERN_SIZE + PATTERN_SPACING);
                y = areaY + EDIT_AREA_PADDING - (int)scrollAmount + row * (PATTERN_SIZE + PATTERN_SPACING);
                visualIndex++;
            }

            EditablePattern ep = editingPatterns.get(i);

            if (y + PATTERN_SIZE >= areaY && y <= this.height - 40) {
                boolean hovered = mouseX >= x && mouseX < x + PATTERN_SIZE &&
                        mouseY >= Math.max(y, areaY) &&
                        mouseY < Math.min(y + PATTERN_SIZE, this.height - 40);

                int bgColor = hovered ? 0xFF3A3A3A : 0xFF2A2A2A;
                guiGraphics.fill(x, y, x + PATTERN_SIZE, y + PATTERN_SIZE, bgColor);

                if (ep.displayText != null) {
                    renderNumber(guiGraphics, ep.displayText, x, y, PATTERN_SIZE);
                } else {
                    PatternRenderer.renderPattern(guiGraphics, ep.pattern, x, y, PATTERN_SIZE, PATTERN_SIZE,
                            PatternRenderer.RenderStyle.STATIC, new PatternRenderer.RenderOptions(), true);
                }
            }

            visualIndex++;
        }

        // Handle insertion at the end
        if (dragManager.isDraggingToEdit() && insertionIndex == visualIndex) {
            // Gap at the end (no visual needed, just for completeness)
        }
    }

    private void renderScrollbar(GuiGraphics guiGraphics, int mouseX, int mouseY, int areaTop, int areaBottom) {
        if (maxScroll <= 0) return;

        int scrollbarX = this.width - 16;
        int scrollbarWidth = 6;
        int scrollbarAreaHeight = areaBottom - areaTop;

        guiGraphics.fill(scrollbarX, areaTop, scrollbarX + scrollbarWidth, areaBottom, 0x60000000);

        int handleHeight = (int)((double)scrollbarAreaHeight * scrollbarAreaHeight / (scrollbarAreaHeight + maxScroll));
        handleHeight = Mth.clamp(handleHeight, 32, scrollbarAreaHeight - 8);

        int handleY = areaTop + (int)((scrollAmount / maxScroll) * (scrollbarAreaHeight - handleHeight));

        boolean isScrolling = smoothScroller.isScrolling();
        int handleColor = (scrolling || isScrolling) ? 0xA0A0A0A0 : 0x80808080;
        guiGraphics.fill(scrollbarX, handleY, scrollbarX + scrollbarWidth, handleY + handleHeight, handleColor);
    }

    private void updateTooltipHover(double mouseX, double mouseY) {
        if (dragManager.isDragging()) {
            hoveredPatternIndex = -1;
            return;
        }

        int newHoveredIndex = getPatternAt(mouseX, mouseY);
        boolean newHoveredInDisposal = isInDisposalArea(mouseY);

        if (newHoveredIndex != hoveredPatternIndex || newHoveredInDisposal != hoveredInDisposal) {
            hoveredPatternIndex = newHoveredIndex;
            hoveredInDisposal = newHoveredInDisposal;
        }
    }

    private void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!Screen.hasShiftDown()) return;
        if (hoveredPatternIndex < 0) return;

        String tooltipText;
        if (hoveredInDisposal && hoveredPatternIndex < disposalPatterns.size()) {
            tooltipText = disposalPatterns.get(hoveredPatternIndex).patternName;
        } else if (!hoveredInDisposal && hoveredPatternIndex < editingPatterns.size()) {
            tooltipText = editingPatterns.get(hoveredPatternIndex).patternName;
        } else {
            return;
        }

        int textWidth = this.font.width(tooltipText);
        int tooltipX = mouseX - textWidth / 2;
        int tooltipY = mouseY - this.font.lineHeight - 8;

        // Symmetrical padding - 3 pixels on both sides
        int padding = 3;

        // Adjust position if it would go off screen
        if (tooltipX - padding < 0) {
            tooltipX = padding;
        }
        if (tooltipX + textWidth + padding > this.width) {
            tooltipX = this.width - textWidth - padding;
        }
        if (tooltipY - padding < 0) {
            tooltipY = mouseY + 12;
        }

        // Push the pose and translate to a higher Z level
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 400); // Move to high Z level (above everything)

        // Draw tooltip background with symmetrical padding
        guiGraphics.fill(tooltipX - padding, tooltipY - padding,
                tooltipX + textWidth + padding, tooltipY + this.font.lineHeight + padding,
                0xF0100010);
        guiGraphics.fill(tooltipX - (padding - 1), tooltipY - (padding - 1),
                tooltipX + textWidth + (padding - 1), tooltipY + this.font.lineHeight + (padding - 1),
                0xF0000000);

        // Draw text in light gray
        guiGraphics.drawString(this.font, tooltipText, tooltipX, tooltipY, 0xCCCCCC);

        guiGraphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int editAreaY = 30 + DISPOSAL_AREA_HEIGHT;
            if (maxScroll > 0 && mouseX >= this.width - 16 && mouseX <= this.width - 10) {
                if (mouseY >= editAreaY && mouseY < this.height - 40) {
                    scrolling = true;
                    return true;
                }
            }

            if (mouseY >= 20 && mouseY < 20 + DISPOSAL_AREA_HEIGHT) {
                int patternIndex = getDisposalPatternAt(mouseX, mouseY);
                if (patternIndex >= 0) {
                    dragManager.startDragFromDisposal(patternIndex, mouseX, mouseY);
                    return true;
                }
            }

            if (mouseY >= editAreaY && mouseY < this.height - 40) {
                int patternIndex = getEditPatternAt(mouseX, mouseY);
                if (patternIndex >= 0) {
                    dragManager.startDragFromEdit(patternIndex, mouseX, mouseY);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrolling) {
            int editAreaY = 30 + DISPOSAL_AREA_HEIGHT;
            int scrollAreaHeight = this.height - 40 - editAreaY;

            double scrollRatio = dragY / scrollAreaHeight;
            double newPos = scrollAmount + scrollRatio * (maxScroll + scrollAreaHeight);
            scrollAmount = Mth.clamp(newPos, 0, maxScroll);
            smoothScroller.stopScroll(); // Stop velocity when manually dragging
            return true;
        }

        if (dragManager.isDragging()) {
            dragManager.updateDrag(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrolling) {
            scrolling = false;
            return true;
        }

        if (dragManager.isDragging()) {
            dragManager.completeDrag();
            updateMaxScroll();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int editAreaY = 30 + DISPOSAL_AREA_HEIGHT;
        if (mouseY >= editAreaY && mouseY < this.height - 40) {
            smoothScroller.addScrollVelocity(delta, PATTERN_ITEM_HEIGHT);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // Add this constant for smooth scrolling
    private static final int PATTERN_ITEM_HEIGHT = PATTERN_SIZE + PATTERN_SPACING;

    private int getPatternAt(double mouseX, double mouseY) {
        if (isInDisposalArea(mouseY)) {
            return getDisposalPatternAt(mouseX, mouseY);
        } else {
            return getEditPatternAt(mouseX, mouseY);
        }
    }

    private boolean isInDisposalArea(double mouseY) {
        return mouseY >= 20 && mouseY < 20 + DISPOSAL_AREA_HEIGHT;
    }

    private int getDisposalPatternAt(double mouseX, double mouseY) {
        int scrollbarSpace = (maxScroll > 0) ? (SCROLLBAR_WIDTH + SCROLLBAR_MARGIN) : 0;
        int availableWidth = this.width - 30 - scrollbarSpace;
        int patternsPerRow = Math.max(1, availableWidth / (PATTERN_SIZE + PATTERN_SPACING));

        int containerWidth = patternsPerRow * PATTERN_SIZE + (patternsPerRow - 1) * PATTERN_SPACING;
        int containerX = 11 + (availableWidth - containerWidth) / 2;

        int x = containerX;
        int y = DISPOSAL_PATTERNS_Y;
        int columnIndex = 0;

        for (int i = 0; i < disposalPatterns.size(); i++) {
            if (mouseX >= x && mouseX < x + PATTERN_SIZE &&
                    mouseY >= y && mouseY < y + PATTERN_SIZE) {
                return i;
            }

            x += PATTERN_SIZE + PATTERN_SPACING;
            columnIndex++;

            if (columnIndex >= patternsPerRow) {
                x = containerX;
                y += PATTERN_SIZE + PATTERN_SPACING;
                columnIndex = 0;
            }
        }
        return -1;
    }

    private int getEditPatternAt(double mouseX, double mouseY) {
        int scrollbarSpace = (maxScroll > 0) ? (SCROLLBAR_WIDTH + SCROLLBAR_MARGIN) : 0;
        int availableWidth = this.width - 30 - scrollbarSpace;
        int patternsPerRow = Math.max(1, availableWidth / (PATTERN_SIZE + PATTERN_SPACING));

        int containerWidth = patternsPerRow * PATTERN_SIZE + (patternsPerRow - 1) * PATTERN_SPACING;
        int containerX = 11 + (availableWidth - containerWidth) / 2;

        int editAreaY = 30 + DISPOSAL_AREA_HEIGHT;
        int x = containerX;
        int y = editAreaY + EDIT_AREA_PADDING - (int)scrollAmount;
        int columnIndex = 0;

        for (int i = 0; i < editingPatterns.size(); i++) {
            if (mouseX >= x && mouseX < x + PATTERN_SIZE &&
                    mouseY >= y && mouseY < y + PATTERN_SIZE) {
                return i;
            }

            x += PATTERN_SIZE + PATTERN_SPACING;
            columnIndex++;

            if (columnIndex >= patternsPerRow) {
                x = containerX;
                y += PATTERN_SIZE + PATTERN_SPACING;
                columnIndex = 0;
            }
        }
        return -1;
    }

    private void updateMaxScroll() {
        int scrollbarSpace = (maxScroll > 0) ? (SCROLLBAR_WIDTH + SCROLLBAR_MARGIN) : 0;
        int availableWidth = this.width - 30 - scrollbarSpace;
        int patternsPerRow = Math.max(1, availableWidth / (PATTERN_SIZE + PATTERN_SPACING));

        int rows = (editingPatterns.size() + patternsPerRow - 1) / patternsPerRow;
        int contentHeight = rows * (PATTERN_SIZE + PATTERN_SPACING) + EDIT_AREA_PADDING * 2;

        int editAreaY = 30 + DISPOSAL_AREA_HEIGHT;
        int viewportHeight = this.height - 40 - editAreaY;

        maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollAmount = Mth.clamp(scrollAmount, 0, maxScroll);
    }

    public double getScrollAmount() {
        return scrollAmount;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parentScreen);
    }

    private void saveAndExit() {
        // If all patterns have been moved to disposal, delete the file
        if (editingPatterns.isEmpty()) {
            PatternStorage.deletePattern(originalPattern, sourceFolder);
            Minecraft.getInstance().setScreen(parentScreen);
            return;
        }

        List<String> newFileLines = new ArrayList<>();
        int patternIndex = 0;

        for (PatternStorage.PatternLine originalLine : allOriginalLines) {
            if (originalLine.isPattern()) {
                if (patternIndex < editingPatterns.size()) {
                    newFileLines.add(editingPatterns.get(patternIndex).originalLine);
                    patternIndex++;
                }
            } else {
                newFileLines.add(originalLine.getRawLine());
            }
        }

        boolean success = PatternStorage.updatePatternFile(
                originalPattern.getName(),
                sourceFolder,
                newFileLines
        );

        if (success) {
            List<HexPattern> updatedPatterns = new ArrayList<>();
            for (EditablePattern ep : editingPatterns) {
                updatedPatterns.add(ep.pattern);
            }
            originalPattern.setPatterns(updatedPatterns);
        }

        Minecraft.getInstance().setScreen(parentScreen);
    }

    void moveFromEditToDisposal(int fromIndex) {
        if (fromIndex >= 0 && fromIndex < editingPatterns.size()) {
            EditablePattern pattern = editingPatterns.remove(fromIndex);
            disposalPatterns.add(pattern);
            updateMaxScroll();
        }
    }

    void moveFromDisposalToEdit(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < disposalPatterns.size()) {
            EditablePattern pattern = disposalPatterns.remove(fromIndex);
            if (toIndex < 0) toIndex = 0;
            if (toIndex > editingPatterns.size()) toIndex = editingPatterns.size();
            editingPatterns.add(toIndex, pattern);
            updateMaxScroll();
        }
    }

    public double getMaxScroll() {
        return maxScroll;
    }

    void reorderInEdit(int from, int to) {
        if (from >= 0 && from < editingPatterns.size() && to >= 0 && to <= editingPatterns.size()) {
            EditablePattern pattern = editingPatterns.remove(from);
            int adjustedTo = to > from ? to - 1 : to;
            editingPatterns.add(adjustedTo, pattern);
        }
    }

    void reorderInDisposal(int from, int to) {
        if (from >= 0 && from < disposalPatterns.size() && to >= 0 && to <= disposalPatterns.size()) {
            EditablePattern pattern = disposalPatterns.remove(from);
            int adjustedTo = to > from ? to - 1 : to;
            disposalPatterns.add(adjustedTo, pattern);
        }
    }

    List<HexPattern> getEditingPatterns() {
        List<HexPattern> patterns = new ArrayList<>();
        for (EditablePattern ep : editingPatterns) {
            patterns.add(ep.pattern);
        }
        return patterns;
    }

    List<HexPattern> getDisposalPatterns() {
        List<HexPattern> patterns = new ArrayList<>();
        for (EditablePattern ep : disposalPatterns) {
            patterns.add(ep.pattern);
        }
        return patterns;
    }

    List<EditablePattern> getEditablePatterns() {
        return editingPatterns;
    }

    List<EditablePattern> getDisposalEditablePatterns() {
        return disposalPatterns;
    }
}