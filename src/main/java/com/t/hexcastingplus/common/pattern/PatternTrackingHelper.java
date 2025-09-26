package com.t.hexcastingplus.common.pattern;
import com.t.hexcastingplus.client.gui.ValidationConstants;

/**
 * Helper class to manage pattern tracking state.
 * Used to coordinate between mixins without violating mixin rules.
 */
public class PatternTrackingHelper {
    private static boolean shouldReset = false;

    public static void markForReset() {
        shouldReset = true;
        PatternCache.clear();
        if (ValidationConstants.DEBUG) {System.out.println("Pattern tracking marked for reset");}
    }

    public static boolean shouldReset() {
        return shouldReset;
    }

    public static void clearResetFlag() {
        shouldReset = false;
    }
}