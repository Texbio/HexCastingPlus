// New file: SmoothScrollHelper.java
package com.t.hexcastingplus.client.gui;

public class SmoothScrollHelper {
    // Constants
    private static final double SCROLL_FRICTION = 0.9;
    private static final double SCROLL_SPEED = 0.1;
    private static final double SCROLL_MULTIPLIER = 1.4;
    private static final double SCROLL_TAILOFF = 0.0002;

    // Instance variables (each scrollable area has its own)
    private double currentScrollVelocity = 0;
    private long lastScrollTime = System.currentTimeMillis();

    /**
     * Update smooth scrolling physics
     * @param currentScroll Current scroll position
     * @param maxScroll Maximum scroll value
     * @return New scroll position
     */
    public double updateSmoothScroll(double currentScroll, double maxScroll) {
        long currentTime = System.currentTimeMillis();
        float deltaTime = (currentTime - lastScrollTime) / 1000f;

        if (deltaTime <= 0 || deltaTime > 0.1f) {
            lastScrollTime = currentTime;
            return currentScroll;
        }

        lastScrollTime = currentTime;

        if (Math.abs(currentScrollVelocity) > SCROLL_TAILOFF) {
            double newPos = currentScroll + currentScrollVelocity * deltaTime * 60;

            // Clamp to bounds
            if (newPos < 0) {
                newPos = 0;
                currentScrollVelocity = 0;
            } else if (newPos > maxScroll) {
                newPos = maxScroll;
                currentScrollVelocity = 0;
            }

            // Apply friction
            double frictionFactor = Math.pow(SCROLL_FRICTION, deltaTime * 60);
            currentScrollVelocity *= frictionFactor;

            if (Math.abs(currentScrollVelocity) < 0.001) {
                currentScrollVelocity = 0;
            }

            return newPos;
        }

        return currentScroll;
    }

    /**
     * Add scroll velocity from mouse wheel
     * @param delta Mouse wheel delta
     * @param itemHeight Height of items being scrolled
     */
    public void addScrollVelocity(double delta, int itemHeight) {
        // Check if shift is held for faster scrolling
        boolean shiftHeld = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        double multiplier = shiftHeld ? 4.0 : 1.0;

        double scrollAmount = delta * SCROLL_SPEED * itemHeight * multiplier;
        currentScrollVelocity -= scrollAmount * SCROLL_MULTIPLIER;
    }

    /**
     * Stop scrolling immediately
     */
    public void stopScroll() {
        currentScrollVelocity = 0;
    }

    /**
     * Check if currently animating
     */
    public boolean isScrolling() {
        return Math.abs(currentScrollVelocity) > 0.001;
    }
}