package com.t.hexcastingplus.common.pattern;

import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class PatternCache {
    // Merged list containing both staff patterns (drawn) and loaded patterns in correct order
    private static List<HexPattern> mergedPatterns = new ArrayList<>();
    private static boolean loadedFromCasted = false;
    private static int loadedPatternCount = 0;

    // A static reference to the currently open staff GUI. This allows other parts
    // of the mod to interact with it, even from a different screen.
    @Nullable
    private static GuiSpellcasting activeStaffGui = null;

    // A list of patterns waiting to be loaded by the Staff GUI once it opens.
    @Nullable
    private static List<HexPattern> pendingPatternsToLoad = null;

    public static boolean isLoadedFromCasted() {
        return loadedFromCasted;
    }

    public static void clearLoadedFromCastedFlag() {
        loadedFromCasted = false;
    }

    public static int getLoadedPatternCount() {
        return loadedPatternCount;
    }

    public static boolean hasPendingPatterns() {
        return pendingPatternsToLoad != null && !pendingPatternsToLoad.isEmpty();
    }

    /**
     * Stores a list of patterns that should be loaded as soon as the Staff GUI is active.
     */
    public static void setPendingPatterns(@Nullable List<HexPattern> patterns, boolean fromCasted) {
        pendingPatternsToLoad = patterns;
        loadedFromCasted = fromCasted;
        loadedPatternCount = fromCasted && patterns != null ? patterns.size() : 0;
    }

    /**
     * Retrieves and clears the list of pending patterns. This ensures they are only loaded once.
     * @return The list of patterns to load, or null if there are none.
     */
    @Nullable
    public static List<HexPattern> pollPendingPatterns() {
        List<HexPattern> patterns = pendingPatternsToLoad;
        pendingPatternsToLoad = null; // Clear the list after retrieving it
        return patterns;
    }

    /**
     * Sets the active staff GUI instance. Called from StaffGuiMixin.onInit().
     */
    public static void setActiveStaffGui(@Nullable GuiSpellcasting gui) {
        activeStaffGui = gui;
    }

    /**
     * Gets the active staff GUI instance. Can be null if it's not open.
     */
    @Nullable
    public static GuiSpellcasting getActiveStaffGui() {
        return activeStaffGui;
    }

    /**
     * Sets the initial patterns when the staff GUI opens.
     * These are patterns already drawn on the staff.
     * Called from StaffGuiMixin.onInit()
     */
    public static void setMergedPatterns(List<HexPattern> patterns) {
        mergedPatterns = new ArrayList<>(patterns);
        if (ValidationConstants.DEBUG) {System.out.println("PatternCache: Set " + patterns.size() + " initial patterns");}
    }

    /**
     * Adds a pattern when it's loaded from saved files.
     * Maintains the order: drawn patterns + loaded patterns.
     * Called from StaffGuiMixin.hexcastingplus$loadPattern()
     */
    public static void addPattern(HexPattern pattern) {
        // Check what serializeToNBT produces
        CompoundTag nbt = pattern.serializeToNBT();
        mergedPatterns.add(pattern);

//        if (ValidationConstants.DEBUG) {
//            System.out.println("=== ADDING TO CACHE ===");
//            System.out.println("Pattern: " + pattern.anglesSignature());
//            System.out.println("StartDir: " + pattern.getStartDir());
//            System.out.println("NBT when cached:");
//            System.out.println("  " + nbt.toString());
//            System.out.println("PatternCache: Added pattern, total now: " + mergedPatterns.size());
//            System.out.println("====================");
//        }

    }

    /**
     * Returns a copy of all cached patterns for saving.
     * Called from SavePatternDialog when user wants to save patterns.
     */
    public static List<HexPattern> getMergedPatterns() {
        return new ArrayList<>(mergedPatterns);
    }

    /**
     * Clears the cache. Called when starting fresh or changing worlds.
     */
    public static void clear() {
        mergedPatterns.clear();
        loadedFromCasted = false;
        if (ValidationConstants.DEBUG) {System.out.println("PatternCache: Cleared");}
    }

    /**
     * Creates a HexPattern from an angle signature (as used in HexActions.java)
     * and adds it to the cache in our compact format.
     *
     * This is useful when loading patterns from the action registry where they're
     * defined using the angle signature format rather than being drawn by users.
     *
     * @param angleSignature The angle string like "qqqqqaqwawaw"
     * @param startDir The starting direction
     * @return The HexPattern created, or null if creation fails
     */
    public static HexPattern addPatternFromAngleSignature(String angleSignature, HexDir startDir) {
        try {
            // Create the HexPattern using the existing fromAngles method
            HexPattern pattern = HexPattern.fromAngles(angleSignature, startDir);

            // Add to our tracked patterns
            addPattern(pattern);

            if (ValidationConstants.DEBUG) {
                System.out.println("Added pattern from angle signature: " + angleSignature);
            }

            return pattern;
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("Failed to add pattern from angle signature: " + e.getMessage());
            }
            return null;
        }
    }
}