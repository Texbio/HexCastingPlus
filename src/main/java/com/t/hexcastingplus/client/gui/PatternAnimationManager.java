package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Manages animation state and rendering for animated patterns.
 * Separated from core rendering logic for better organization.
 */
public class PatternAnimationManager {

    // Animation configuration
    private static final float ANIMATION_DURATION = 500f;
    private static final float SPARK_LIFETIME = 10000f;
    private static final int SPARKS_PER_SEGMENT = 5;
    private static final float COOL_DOWN_DURATION = 250f;

    // Animation state maps
    private static final Map<String, AnimationState> animations = new HashMap<>();
    private static final Map<String, List<Spark>> activeSparks = new HashMap<>();
    private static long lastCleanupTime = 0;

    /**
     * Render an animated pattern with heat effects and sparks
     */
    public static void renderAnimatedPattern(GuiGraphics guiGraphics, HexPattern pattern,
                                             List<Vec2> points, int x, int y, int width, int height,
                                             String animationKey, float fadeAlpha) {
        if (points.size() < 2) return;

        // Get or create animation state
        AnimationState state = animations.computeIfAbsent(animationKey, k -> new AnimationState());

        // Update animation
        state.update();

        // Render based on animation phase
        if (state.isComplete()) {
            // Render static lines when animation is done
            renderStaticLines(guiGraphics, points, fadeAlpha);
        } else {
            // Render animated lines with heat effect
            renderAnimatedLines(guiGraphics, points, state, fadeAlpha);

            // Update spark spawning during animation (not during cooldown)
            if (!state.isCoolingDown()) {
                updateSparkSpawning(animationKey, points, state.getProgress());
            }
        }

        // IMPORTANT: Always update and render sparks, even after animation completes!
        // Sparks have their own lifetime independent of the animation
        renderSparks(guiGraphics, animationKey, fadeAlpha);

        // Periodic cleanup
        if (System.currentTimeMillis() - lastCleanupTime > 1000) {
            cleanupOldAnimations();
            lastCleanupTime = System.currentTimeMillis();
        }
    }

    /**
     * Clear all animation state
     */
    public static void clearAllAnimations() {
        animations.clear();
        activeSparks.clear();
    }

    /**
     * Clear only active animations, preserve completed ones
     */
    public static void clearActiveAnimations() {
        animations.entrySet().removeIf(entry -> !entry.getValue().isComplete());
        // Don't clear sparks here - let them live out their natural lifetime
        // activeSparks.clear();  // REMOVED THIS LINE
    }

    // Private rendering methods

    private static void renderStaticLines(GuiGraphics guiGraphics, List<Vec2> points, float fadeAlpha) {
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

    private static void renderAnimatedLines(GuiGraphics guiGraphics, List<Vec2> points,
                                            AnimationState state, float fadeAlpha) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        float progress = state.getProgress();
        float totalLength = calculateTotalLength(points);
        float drawnLength = totalLength * progress;
        float currentLength = 0;

        for (int i = 0; i < points.size() - 1; i++) {
            Vec2 from = points.get(i);
            Vec2 to = points.get(i + 1);
            float segmentLength = distance(from, to);

            if (currentLength < drawnLength) {
                Vec2 endPoint = to;
                if (currentLength + segmentLength > drawnLength) {
                    // Partial segment
                    float t = (drawnLength - currentLength) / segmentLength;
                    endPoint = new Vec2(
                            from.x + (to.x - from.x) * t,
                            from.y + (to.y - from.y) * t
                    );
                }

                int color = getHeatColor(state, currentLength / totalLength);
                float r = ((color >> 16) & 0xFF) / 255.0f;
                float g = ((color >> 8) & 0xFF) / 255.0f;
                float b = (color & 0xFF) / 255.0f;

                bufferBuilder.vertex(matrix, from.x, from.y, 0).color(r, g, b, fadeAlpha).endVertex();
                bufferBuilder.vertex(matrix, endPoint.x, endPoint.y, 0).color(r, g, b, fadeAlpha).endVertex();
            }

            currentLength += segmentLength;
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    private static void updateSparkSpawning(String animKey, List<Vec2> points, float progress) {
        String sparkKey = animKey + "_sparks";
        List<Spark> sparks = activeSparks.computeIfAbsent(sparkKey, k -> new ArrayList<>());

        // Simple spark spawning at line endpoints during animation
        if (progress > 0.1f && sparks.size() < SPARKS_PER_SEGMENT * points.size()) {
            Random rand = new Random();
            int pointIndex = Math.min((int)(progress * points.size()), points.size() - 1);
            Vec2 point = points.get(pointIndex);

            for (int i = 0; i < SPARKS_PER_SEGMENT / 5; i++) {
                float sparkX = point.x + (rand.nextFloat() - 0.5f) * 6;
                float sparkY = point.y + (rand.nextFloat() - 0.5f) * 6;
                sparks.add(new Spark(sparkX, sparkY));
            }
        }
    }

    private static void renderSparks(GuiGraphics guiGraphics, String animKey, float fadeAlpha) {
        String sparkKey = animKey + "_sparks";
        List<Spark> sparks = activeSparks.get(sparkKey);
        if (sparks == null || sparks.isEmpty()) return;

        // Update and remove dead sparks
        sparks.removeIf(spark -> {
            spark.update(0.016f);
            return spark.isDead();
        });

        // Render remaining sparks
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (Spark spark : sparks) {
            int color = spark.getColor();
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;
            float a = ((color >> 24) & 0xFF) / 255.0f * fadeAlpha;

            float x = spark.x;
            float y = spark.y;

            bufferBuilder.vertex(matrix, x, y + 1, 0).color(r, g, b, a).endVertex();
            bufferBuilder.vertex(matrix, x + 1, y + 1, 0).color(r, g, b, a).endVertex();
            bufferBuilder.vertex(matrix, x + 1, y, 0).color(r, g, b, a).endVertex();
            bufferBuilder.vertex(matrix, x, y, 0).color(r, g, b, a).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    private static int getHeatColor(AnimationState state, float segmentProgress) {
        if (state.isCoolingDown()) {
            // Cooling down - fade to white
            float coolProgress = state.getCooldownProgress();
            int r = (int)(0x88 + (0xFF - 0x88) * coolProgress);
            int g = (int)(0x11 + (0xFF - 0x11) * coolProgress);
            int b = (int)(0x00 + (0xFF - 0x00) * coolProgress);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } else {
            // Heating up - hot colors
            float heat = 1.0f - segmentProgress;
            if (heat > 0.9f) return 0xFFFFFFF0; // White-yellow
            else if (heat > 0.7f) return 0xFFFFAA00; // Orange
            else if (heat > 0.5f) return 0xFFFF4400; // Red-orange
            else if (heat > 0.3f) return 0xFFCC2200; // Red
            else return 0xFF881100; // Dark red
        }
    }

    private static float calculateTotalLength(List<Vec2> points) {
        float total = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += distance(points.get(i), points.get(i + 1));
        }
        return total;
    }

    private static float distance(Vec2 a, Vec2 b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        return (float)Math.sqrt(dx * dx + dy * dy);
    }

    private static void cleanupOldAnimations() {
        // Remove spark lists that are empty AND whose animation has been complete for a while
        activeSparks.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            List<Spark> sparks = entry.getValue();

            // Only remove if the list is empty
            if (!sparks.isEmpty()) {
                return false;
            }

            // Extract the animation key from the spark key (remove "_sparks" suffix)
            String animKey = key.replace("_sparks", "");
            AnimationState state = animations.get(animKey);

            // Only remove empty spark lists if the animation has been complete for at least 15 seconds
            // This gives all sparks time to die naturally
            if (state != null && state.isComplete()) {
                return state.getTimeSinceComplete() > 15000; // 15 seconds after animation completes
            }

            return false;
        });

        // Don't remove animation states - they should persist to prevent replaying
    }

    /**
     * Animation state for a single pattern
     */
    private static class AnimationState {
        private long startTime;
        private float elapsed = 0;
        private boolean complete = false;
        private long completeTime = 0;

        public AnimationState() {
            this.startTime = System.currentTimeMillis();
        }

        public void update() {
            long now = System.currentTimeMillis();
            elapsed = (now - startTime) / 1000f;

            if (elapsed > (ANIMATION_DURATION + COOL_DOWN_DURATION) / 1000f) {
                if (!complete) {
                    complete = true;
                    completeTime = now;
                }
            }
        }

        public float getProgress() {
            return Math.min(1.0f, elapsed / (ANIMATION_DURATION / 1000f));
        }

        public boolean isCoolingDown() {
            return elapsed > (ANIMATION_DURATION / 1000f);
        }

        public float getCooldownProgress() {
            if (!isCoolingDown()) return 0;
            float coolElapsed = elapsed - (ANIMATION_DURATION / 1000f);
            return Math.min(1.0f, coolElapsed / (COOL_DOWN_DURATION / 1000f));
        }

        public boolean isComplete() {
            return complete;
        }

        public long getTimeSinceComplete() {
            return complete ? System.currentTimeMillis() - completeTime : 0;
        }
    }

    /**
     * Simple spark particle
     */
    private static class Spark {
        float x, y;
        float vx, vy;
        long birthTime;
        float lifetime;

        Spark(float x, float y) {
            this.x = x;
            this.y = y;
            this.birthTime = System.currentTimeMillis();

            Random rand = new Random();
            this.vx = (rand.nextFloat() - 0.5f) * 80;
            this.vy = -60 - rand.nextFloat() * 80;
            this.lifetime = SPARK_LIFETIME * (0.7f + rand.nextFloat() * 0.6f);
        }

        void update(float deltaTime) {
            vy += 300 * deltaTime; // Gravity
            vx *= (1.0f - 0.3f * deltaTime); // Air resistance
            x += vx * deltaTime;
            y += vy * deltaTime;
        }

        int getColor() {
            float age = (System.currentTimeMillis() - birthTime) / 1000f;
            float lifeRatio = age / (lifetime / 1000f);

            int color;
            if (lifeRatio < 0.15f) color = 0xFFFFF0;
            else if (lifeRatio < 0.3f) color = 0xFFAA00;
            else if (lifeRatio < 0.6f) color = 0xFF4400;
            else color = 0xFF2200;

            int alpha = 255;
            if (lifeRatio > 0.7f) {
                alpha = (int)((1.0f - lifeRatio) / 0.3f * 255);
            }

            return (alpha << 24) | (color & 0xFFFFFF);
        }

        boolean isDead() {
            float age = (System.currentTimeMillis() - birthTime) / 1000f;
            return age > lifetime / 1000f;
        }
    }
}