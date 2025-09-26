package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Core pattern rendering engine.
 * Handles all pattern drawing operations without managing UI state.
 */
public class PatternRenderer {

    // Cache for pattern dimensions to avoid recalculation
    private static final Map<String, PatternDimensions> dimensionCache = new HashMap<>();
    private static final int MAX_CACHE_SIZE = 500;

    // Rendering constants
    private static final float BASE_LINE_THICKNESS = 0.5f;
    private static final float LINE_THICKNESS_SCALE_THRESHOLD = 4.0f;
    private static final float THICK_LINE_MULTIPLIER = 1.5f;

    // Access to HexCoord private fields (initialized once)
    private static Field qField;
    private static Field rField;

    static {
        try {
            qField = HexCoord.class.getDeclaredField("q");
            qField.setAccessible(true);
            rField = HexCoord.class.getDeclaredField("r");
            rField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Render a single pattern with specified options (original method)
     */
    public static void renderPattern(GuiGraphics guiGraphics, HexPattern pattern,
                                     int x, int y, int width, int height,
                                     RenderStyle style, RenderOptions options) {
        renderPattern(guiGraphics, pattern, x, y, width, height, style, options, false);
    }

    /**
     * Render a single pattern with specified options and width constraint flag
     */
    public static void renderPattern(GuiGraphics guiGraphics, HexPattern pattern,
                                     int x, int y, int width, int height,
                                     RenderStyle style, RenderOptions options,
                                     boolean constrainWidth) {
        if (pattern == null) return;

        List<HexCoord> positions = pattern.positions();
        if (positions.isEmpty()) return;

        // Calculate scale based on constraint flag
        float hexSize;
        if (constrainWidth) {
            hexSize = calculateHexSize(pattern, height, width);
        } else {
            hexSize = calculateHexSize(pattern, height);
        }

        List<Vec2> centeredPoints = calculateCenteredPoints(pattern, x, y, width, height, hexSize);

        // Render based on style
        switch (style) {
            case STATIC:
                renderStaticPattern(guiGraphics, centeredPoints, options.fadeAlpha);
                break;
            case ANIMATED:
                PatternAnimationManager.renderAnimatedPattern(
                        guiGraphics, pattern, centeredPoints, x, y, width, height,
                        options.animationKey, options.fadeAlpha
                );
                break;
            case TOOLTIP:
                renderTooltipPattern(guiGraphics, centeredPoints, options.fadeAlpha);
                break;
            case PREVIEW:
                renderPreviewPattern(guiGraphics, centeredPoints);
                break;
        }
    }

    /**
     * Render multiple patterns in a grid layout
     */
    public static void renderPatternGrid(GuiGraphics guiGraphics, List<HexPattern> patterns,
                                         int x, int y, int maxWidth, int height,
                                         GridLayout layout, RenderOptions options) {
        if (patterns.isEmpty()) return;

        int currentX = x;
        int patternsRendered = 0;

        for (int i = 0; i < patterns.size() && patternsRendered < layout.maxPatterns; i++) {
            HexPattern pattern = patterns.get(i);
            PatternDimensions dims = calculateDimensions(pattern, height);

            // Check if we have room
            if (currentX + dims.width > x + maxWidth && i > 0) {
                // Would overflow, stop here
                break;
            }

            // Render the pattern
            renderPattern(guiGraphics, pattern, currentX, y, dims.width, height,
                    layout.style, options.withAnimationKey(options.animationKey + "_" + i));

            currentX += dims.width + layout.spacing;
            patternsRendered++;
        }

        // Return info about what was rendered
        layout.patternsRendered = patternsRendered;
        layout.finalWidth = currentX - x;
    }

    /**
     * Calculate pattern dimensions at a given scale
     */
    public static PatternDimensions calculateDimensions(HexPattern pattern, int maxHeight) {
        if (pattern == null) return new PatternDimensions(8, maxHeight, 1.0f);

        // Check cache
        String cacheKey = pattern.hashCode() + "_" + maxHeight;
        PatternDimensions cached = dimensionCache.get(cacheKey);
        if (cached != null) return cached;

        // Clean cache if too large
        if (dimensionCache.size() > MAX_CACHE_SIZE) {
            dimensionCache.clear();
        }

        List<HexCoord> positions = pattern.positions();
        if (positions.isEmpty()) {
            PatternDimensions dims = new PatternDimensions(8, maxHeight, 1.0f);
            dimensionCache.put(cacheKey, dims);
            return dims;
        }

        float hexSize = calculateHexSize(pattern, maxHeight);

        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

        for (HexCoord coord : positions) {
            Vec2 point = hexCoordToScreen(coord, hexSize);
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }

        int width = Math.max(8, (int)Math.ceil(maxX - minX) + 4);
        int height = Math.max(8, (int)Math.ceil(maxY - minY) + 4);

        PatternDimensions dims = new PatternDimensions(width, height, hexSize);
        dimensionCache.put(cacheKey, dims);
        return dims;
    }

    /**
     * Convert hex coordinate to screen position
     */
    public static Vec2 hexCoordToScreen(HexCoord coord, float size) {
        int q = getQ(coord);
        int r = getR(coord);

        // Pointy-top hexagon formula
        float x = size * (Mth.sqrt(3) * q + Mth.sqrt(3)/2 * r);
        float y = size * (3.0f/2 * r);

        return new Vec2(x, y);
    }

    /**
     * Calculate appropriate hex size for pattern to fit in given height (original method)
     */
    public static float calculateHexSize(HexPattern pattern, int maxHeight) {
        return calculateHexSize(pattern, maxHeight, Integer.MAX_VALUE);
    }

    public static float calculateHexSize(HexPattern pattern, int maxHeight, int maxWidth) {
        List<HexCoord> positions = pattern.positions();
        if (positions.isEmpty()) return 5.0f;

        // Get bounds on both axes
        int minQ = positions.stream().mapToInt(PatternRenderer::getQ).min().orElse(0);
        int maxQ = positions.stream().mapToInt(PatternRenderer::getQ).max().orElse(0);
        int minR = positions.stream().mapToInt(PatternRenderer::getR).min().orElse(0);
        int maxR = positions.stream().mapToInt(PatternRenderer::getR).max().orElse(0);

        float qRange = maxQ - minQ;
        float rRange = maxR - minR;

        // Calculate pattern dimensions in hex coordinates
        float hexWidth = Mth.sqrt(3) * qRange + Mth.sqrt(3)/2 * Math.abs(rRange);
        float hexHeight = rRange * 1.5f + 1;

        // Calculate the hex size needed to fit - REMOVED 0.8f padding multiplier
        float hexSizeForHeight = maxHeight / Math.max(hexHeight, 1);

        // If width constraint provided, also calculate for width
        float hexSizeForWidth = Float.MAX_VALUE;
        if (maxWidth != Integer.MAX_VALUE) {
            hexSizeForWidth = maxWidth / Math.max(hexWidth, 1);  // REMOVED 0.8f here too
        }

        // Use the smaller of the two to ensure it fits both dimensions
        float hexSize = Math.min(hexSizeForHeight, hexSizeForWidth);
        return Math.min(hexSize, 5.0f);  // Cap at 5.0f
    }

    /**
     * Render a mini pattern preview (simplified for list views)
     */
    public static void renderMiniPattern(GuiGraphics guiGraphics, HexPattern pattern,
                                         int x, int y, int size) {
        if (pattern == null) return;

        List<HexCoord> positions = pattern.positions();
        if (positions.isEmpty()) return;

        // Calculate points scaled to fit in the small preview area
        float hexSize = calculateHexSize(pattern, size, size);
        List<Vec2> points = calculateCenteredPoints(pattern, x, y, size, size, hexSize);

        // Draw simplified pattern with thinner lines
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < points.size() - 1; i++) {
            Vec2 from = points.get(i);
            Vec2 to = points.get(i + 1);
            bufferBuilder.vertex(matrix, from.x, from.y, 0).color(0.7f, 0.7f, 1.0f, 0.8f).endVertex();
            bufferBuilder.vertex(matrix, to.x, to.y, 0).color(0.7f, 0.7f, 1.0f, 0.8f).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    private static void renderStaticPattern(GuiGraphics guiGraphics, List<Vec2> points, float fadeAlpha) {
        if (points.size() < 2) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < points.size() - 1; i++) {
            Vec2 from = points.get(i);
            Vec2 to = points.get(i + 1);
            bufferBuilder.vertex(matrix, from.x, from.y, 0).color(1.0f, 1.0f, 1.0f, fadeAlpha).endVertex();
            bufferBuilder.vertex(matrix, to.x, to.y, 0).color(1.0f, 1.0f, 1.0f, fadeAlpha).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    private static void renderTooltipPattern(GuiGraphics guiGraphics, List<Vec2> points, float fadeAlpha) {
        if (points.size() < 2) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        float tooltipAlpha = fadeAlpha * 0.5f; // Tooltip patterns are more subtle

        for (int i = 0; i < points.size() - 1; i++) {
            Vec2 from = points.get(i);
            Vec2 to = points.get(i + 1);
            bufferBuilder.vertex(matrix, from.x, from.y, 0).color(1.0f, 1.0f, 1.0f, tooltipAlpha).endVertex();
            bufferBuilder.vertex(matrix, to.x, to.y, 0).color(1.0f, 1.0f, 1.0f, tooltipAlpha).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    private static void renderPreviewPattern(GuiGraphics guiGraphics, List<Vec2> points) {
        renderStaticPattern(guiGraphics, points, 1.0f);
    }

    private static List<Vec2> calculateCenteredPoints(HexPattern pattern, int x, int y,
                                                      int width, int height, float hexSize) {
        List<HexCoord> positions = pattern.positions();
        List<Vec2> screenPoints = new ArrayList<>();

        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

        for (HexCoord coord : positions) {
            Vec2 point = hexCoordToScreen(coord, hexSize);
            screenPoints.add(point);
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }

        float offsetX = x + width / 2.0f - (minX + maxX) / 2.0f;
        float offsetY = y + height / 2.0f - (minY + maxY) / 2.0f;

        List<Vec2> centeredPoints = new ArrayList<>();
        for (Vec2 point : screenPoints) {
            centeredPoints.add(new Vec2(point.x + offsetX, point.y + offsetY));
        }

        return centeredPoints;
    }

    // Utility methods

    private static int getQ(HexCoord coord) {
        try {
            return qField.getInt(coord);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int getR(HexCoord coord) {
        try {
            return rField.getInt(coord);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Rendering style enumeration
     */
    public enum RenderStyle {
        STATIC,      // Simple white lines
        ANIMATED,    // With heat effects and sparks
        TOOLTIP,     // Semi-transparent for tooltips
        PREVIEW      // For save/load dialogs
    }

    /**
     * Container for rendering options
     */
    public static class RenderOptions {
        public float fadeAlpha = 1.0f;
        public String animationKey = "";
        public boolean isFullyVisible = true;

        public RenderOptions withFadeAlpha(float alpha) {
            this.fadeAlpha = alpha;
            return this;
        }

        public RenderOptions withAnimationKey(String key) {
            this.animationKey = key;
            return this;
        }

        public RenderOptions withVisibility(boolean visible) {
            this.isFullyVisible = visible;
            return this;
        }
    }

    /**
     * Grid layout configuration
     */
    public static class GridLayout {
        public final int maxPatterns;
        public final int spacing;
        public final RenderStyle style;

        // Output values
        public int patternsRendered = 0;
        public int finalWidth = 0;

        public GridLayout(int maxPatterns, int spacing, RenderStyle style) {
            this.maxPatterns = maxPatterns;
            this.spacing = spacing;
            this.style = style;
        }
    }

    /**
     * Pattern dimension data
     */
    public static class PatternDimensions {
        public final int width;
        public final int height;
        public final float scale;

        public PatternDimensions(int width, int height, float scale) {
            this.width = width;
            this.height = height;
            this.scale = scale;
        }
    }
}