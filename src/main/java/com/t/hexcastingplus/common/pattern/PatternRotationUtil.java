package com.t.hexcastingplus.common.pattern;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;

public class PatternRotationUtil {

    /**
     * Rotate a pattern by 60-degree increments.
     * Used by HexPatternSelectorScreen to find canonical forms.
     */
    public static HexPattern rotatePattern(HexPattern pattern, int rotations) {
        if (rotations == 0) return pattern;

        String sig = pattern.anglesSignature();
        HexDir dir = PatternStorage.getPatternStartDir(pattern);

        // Rotate the start direction
        for (int i = 0; i < rotations; i++) {
            dir = dir.rotatedBy(HexAngle.RIGHT);
        }

        // Rotate each angle in the signature
        StringBuilder rotatedSig = new StringBuilder();
        for (char c : sig.toCharArray()) {
            rotatedSig.append(rotateAngle(c, rotations));
        }

        return HexPattern.fromAngles(rotatedSig.toString(), dir);
    }

    private static char rotateAngle(char angle, int rotations) {
        int ordinal;
        switch (angle) {
            case 'w': ordinal = 0; break;
            case 'e': ordinal = 1; break;
            case 'd': ordinal = 2; break;
            case 's': ordinal = 3; break;
            case 'a': ordinal = 4; break;
            case 'q': ordinal = 5; break;
            default: return angle;
        }

        ordinal = (ordinal + rotations) % 6;

        switch (ordinal) {
            case 0: return 'w';
            case 1: return 'e';
            case 2: return 'd';
            case 3: return 's';
            case 4: return 'a';
            case 5: return 'q';
            default: return angle;
        }
    }

    /**
     * Get the starting direction from a HexPattern
     */
    public static HexDir getStartDir(HexPattern pattern) {
        return PatternStorage.getPatternStartDir(pattern);
    }
}