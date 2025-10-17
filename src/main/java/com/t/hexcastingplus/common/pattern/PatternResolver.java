package com.t.hexcastingplus.common.pattern;

import com.t.hexcastingplus.common.pattern.PatternErrorHandler;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.eval.SpecialPatterns;
import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.t.hexcastingplus.client.NumberPatternGenerator;
import com.t.hexcastingplus.client.bruteforce.BruteforceManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public class PatternResolver {
    private static Set<String> reportedErrors = new HashSet<>();
    private static String currentParsingFile = null;
    private static Map<String, PatternInfo> patternRegistry = null;
    private static Map<String, String> specialPatternNames = new HashMap<>();
    // Add a cache for angle signature -> name mapping
    private static Map<String, String> angleSignatureCache = null;

    public static class PatternInfo {
        public final String name;
        public final String signature;
        public final HexDir startDir;
        public final boolean isPerWorldPattern;
        public final String resourceLocation;

        public PatternInfo(String name, String signature, HexDir startDir, boolean isPerWorldPattern, String resourceLocation) {
            this.name = name;
            this.signature = signature;
            this.startDir = startDir;
            this.isPerWorldPattern = isPerWorldPattern;
            this.resourceLocation = resourceLocation;
        }
    }

    static {
        // Initialize special pattern names
        specialPatternNames.put("qqq", "Introspection");
        specialPatternNames.put("eee", "Retrospection");
        specialPatternNames.put("qqqaw", "Consideration");
        specialPatternNames.put("qqq", "Evanition");
    }

    private static HexPattern findPatternByName(String name, String filename) {
        for (PatternInfo info : patternRegistry.values()) {
            if (info.name.equalsIgnoreCase(name)) {
                // Check if it's a great spell
                if (info.isPerWorldPattern) {
                    String solvedSig = BruteforceManager.getInstance().getSolvedSignature(info.resourceLocation);
                    if (solvedSig != null) {
                        return BruteforceManager.getInstance().signatureToPattern(solvedSig);
                    } else {
                        // Use the centralized error handler
                        PatternErrorHandler.reportGreatSpellError(filename, name);
                        return null;
                    }
                }
                return HexPattern.fromAngles(info.signature, info.startDir);
            }
        }
        return null;
    }

    /**
     * Initialize the pattern registry from HexActions
     */
    public static void initializeRegistry() {
        if (patternRegistry != null) return;

        patternRegistry = new HashMap<>();
        angleSignatureCache = new HashMap<>();
        var registry = IXplatAbstractions.INSTANCE.getActionRegistry();

        for (var entry : registry.entrySet()) {
            ResourceKey<ActionRegistryEntry> resourceKey = entry.getKey();
            ActionRegistryEntry actionEntry = entry.getValue();

            if (actionEntry == null || actionEntry.prototype() == null) continue;

            HexPattern pattern = actionEntry.prototype();
            ResourceLocation resourceLoc = resourceKey.location();
            String resourcePath = resourceLoc.getPath();

            boolean isPerWorldPattern = false;
            try {
                isPerWorldPattern = registry.getHolderOrThrow(resourceKey).is(HexTags.Actions.PER_WORLD_PATTERN);
            } catch (Exception e) {
            }

            String translationKey = "hexcasting.action." + resourceLoc.toString();
            String translatedName = net.minecraft.client.resources.language.I18n.get(translationKey);

            String displayName;
            if (translatedName.equals(translationKey)) {
                displayName = formatResourcePath(resourcePath);
            } else {
                displayName = translatedName;
            }

            String signature = pattern.anglesSignature();
            HexDir startDir = PatternStorage.getPatternStartDir(pattern);
            String key = signature + "," + startDir.name();

            patternRegistry.put(key, new PatternInfo(
                    displayName,
                    signature,
                    startDir,
                    isPerWorldPattern,
                    resourceLoc.toString()
            ));

            angleSignatureCache.put(signature, displayName);
        }

        addSpecialPattern(SpecialPatterns.INTROSPECTION, "Introspection");
        addSpecialPattern(SpecialPatterns.RETROSPECTION, "Retrospection");
        addSpecialPattern(SpecialPatterns.CONSIDERATION, "Consideration");
        addSpecialPattern(SpecialPatterns.EVANITION, "Evanition");

        System.out.println("[PatternResolver] Initialized with " + patternRegistry.size() + " patterns");
    }

    public static String resolveFromAngleSignature(String angleSignature, HexDir startDir) {
        if (angleSignatureCache == null) {
            initializeRegistry();
        }

        // Check for number patterns first
        if (angleSignature.startsWith("aqaa") || angleSignature.startsWith("dedd")) {
            boolean negative = angleSignature.startsWith("dedd");
            String workingPart = angleSignature.substring(4);

            double value = 0;
            for (char c : workingPart.toCharArray()) {
                switch (c) {
                    case 'w': value += 1; break;
                    case 'q': value += 5; break;
                    case 'e': value += 10; break;
                    case 'a': value *= 2; break;
                    case 'd': value /= 2; break;
                }
            }

            if (negative) value = -value;

            if (value == Math.floor(value) && !Double.isInfinite(value)) {
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return "Numerical Reflection: " + (int)value;
                } else {
                    return "Numerical Reflection: " + (long)value;
                }
            } else {
                return "Numerical Reflection: " + value;
            }
        }

        // Check for Bookkeeper's Gambit with proper validation
        String bookkeepers = resolveBookkeepersGambit(angleSignature, startDir);
        if (bookkeepers != null) {
            return bookkeepers;
        }

        // First check the cache for exact match
        String cached = angleSignatureCache.get(angleSignature);
        if (cached != null) {
            return cached;
        }

        // Check if this angle signature matches ANY registered pattern (rotation-independent)
        for (PatternInfo info : patternRegistry.values()) {
            if (info.signature.equals(angleSignature)) {
                // Check if it's a solved great spell
                if (info.isPerWorldPattern && info.resourceLocation != null) {
                    String solvedEntry = BruteforceManager.getInstance().getSolvedSignature(info.resourceLocation);
                    if (solvedEntry != null) {
                        String solvedSig = solvedEntry.contains(",") ?
                                solvedEntry.substring(0, solvedEntry.indexOf(",")) :
                                solvedEntry;
                        if (solvedSig.equals(angleSignature)) {
                            return info.name;
                        }
                    }
                    // Even if not solved, we found the pattern by signature
                    return info.name;
                }
                // Regular pattern found by signature
                return info.name;
            }
        }

        return null;
    }

    private static void addSpecialPattern(HexPattern pattern, String name) {
        String signature = pattern.anglesSignature();
        HexDir startDir = PatternStorage.getPatternStartDir(pattern);
        String key = signature + "," + startDir.name();

        patternRegistry.put(key, new PatternInfo(
                name,
                signature,
                startDir,
                false,
                null
        ));

        angleSignatureCache.put(signature, name);
    }

    private static String formatResourcePath(String path) {
        // Convert snake_case to Title Case
        String[] parts = path.split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                result.append(part.substring(1));
                result.append(" ");
            }
        }
        return result.toString().trim();
    }

    public static String resolvePattern(HexPattern pattern) {
        if (patternRegistry == null) {
            initializeRegistry();
        }

        String signature = pattern.anglesSignature();
        HexDir startDir = PatternStorage.getPatternStartDir(pattern);

        // 1. Check if it's a number
        Double number = resolveNumberPattern(pattern);
        if (number != null) {
            if (number == Math.floor(number) && !Double.isInfinite(number)) {
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                    return "Numerical Reflection: " + number.intValue();
                } else {
                    return "Numerical Reflection: " + number.longValue();
                }
            } else {
                return "Numerical Reflection: " + number;
            }
        }

        // 2. Check if it's Bookkeeper's Gambit
        String bookkeepers = resolveBookkeepersGambit(signature, startDir);
        if (bookkeepers != null) {
            return bookkeepers;
        }

        // 3. Check bruteforce cache for great spells
        String patternKey = signature + "," + startDir.name();
        for (PatternInfo info : patternRegistry.values()) {
            if (info.isPerWorldPattern && info.resourceLocation != null) {
                String solvedEntry = BruteforceManager.getInstance().getSolvedSignature(info.resourceLocation);
                if (solvedEntry != null && solvedEntry.equals(patternKey)) {
                    return info.name;
                }
            }
        }

        // 4. Check registry - but now check ALL registered patterns regardless of rotation
        for (PatternInfo info : patternRegistry.values()) {
            if (info.signature.equals(signature)) {
                // Found a match by signature - it's the same pattern, just rotated
                if (info.isPerWorldPattern) {
                    // Verify great spell is solved before allowing save
                    String solvedSig = BruteforceManager.getInstance().getSolvedSignature(info.resourceLocation);
                    if (solvedSig == null) {
                        System.out.println("[PatternResolver] ERROR: Great spell '" + info.name +
                                "' not solved via bruteforce. Cannot save.");
                        return null;
                    }
                }
                return info.name;  // Return the name regardless of rotation
            }
        }

        return null; // Unknown pattern
    }

    public static List<HexPattern> parsePattern(String line, String filename) {
        if (patternRegistry == null) {
            initializeRegistry();
        }

        line = line.trim();

        // Skip comments and empty lines
        if (line.isEmpty() || line.startsWith("//")) {
            return null;
        }

        // Remove inline comments
        int commentIndex = line.indexOf("//");
        if (commentIndex > 0) {
            line = line.substring(0, commentIndex).trim();
        }

        // 1. Check special shortcuts
        if (line.equals("{")) {
            HexPattern pattern = findPatternByName("Introspection", filename);
            return pattern != null ? Collections.singletonList(pattern) : null;
        }
        if (line.equals("}")) {
            HexPattern pattern = findPatternByName("Retrospection", filename);
            return pattern != null ? Collections.singletonList(pattern) : null;
        }

        // 2. Check for number format
        if (line.startsWith("Numerical Reflection:")) {
            String numStr = line.substring("Numerical Reflection:".length()).trim();
            try {
                double value = Double.parseDouble(numStr);

                // Always use sequence approach for all numbers
                NumberPatternGenerator.PatternSequence sequence = NumberPatternGenerator.convertToPatternSequence(value);
                if (sequence != null && !sequence.components.isEmpty()) {
                    List<HexPattern> patterns = new ArrayList<>();
                    for (NumberPatternGenerator.PatternSequence.PatternComponent component : sequence.components) {
                        patterns.add(HexPattern.fromAngles(component.pattern, component.startDir));
                    }
                    return patterns;
                }
            } catch (NumberFormatException e) {
                // Silent - not a valid number
            }
        } else {
            // Try to parse as just a number
            try {
                double value = Double.parseDouble(line);

                NumberPatternGenerator.PatternSequence sequence = NumberPatternGenerator.convertToPatternSequence(value);
                if (sequence != null && !sequence.components.isEmpty()) {
                    List<HexPattern> patterns = new ArrayList<>();
                    for (NumberPatternGenerator.PatternSequence.PatternComponent component : sequence.components) {
                        patterns.add(HexPattern.fromAngles(component.pattern, component.startDir));
                    }
                    return patterns;
                }
            } catch (NumberFormatException e) {
                // Not a number, continue
            }
        }

        // 3. Try to find pattern by name
        HexPattern pattern = findPatternByName(line, filename);
        if (pattern != null) {
            return Collections.singletonList(pattern);
        }

        // 4. Check for Bookkeeper's Gambit format
        if (line.startsWith("Bookkeeper's Gambit:")) {
            String format = line.substring("Bookkeeper's Gambit:".length()).trim();
            pattern = parseBookkeepersGambit(format);
            return pattern != null ? Collections.singletonList(pattern) : null;
        }

        // 5. Fallback to raw angle signature format (angles,DIRECTION)
        if (line.contains(",")) {
            pattern = PatternStorage.angleSignatureToHexPattern(line);
            return pattern != null ? Collections.singletonList(pattern) : null;
        }

        // Don't report parse errors here - let PatternStorage handle it
        return null;
    }

    // Keep backward compatibility - overload without filename
    public static List<HexPattern> parsePattern(String line) {
        return parsePattern(line, null);
    }

    private static HexPattern findPatternByName(String name) {
        for (PatternInfo info : patternRegistry.values()) {
            if (info.name.equalsIgnoreCase(name)) {
                // Check if it's a great spell
                if (info.isPerWorldPattern) {
                    String solvedSig = BruteforceManager.getInstance().getSolvedSignature(info.resourceLocation);
                    if (solvedSig != null) {
                        return BruteforceManager.getInstance().signatureToPattern(solvedSig);
                    } else {
                        System.out.println("[PatternResolver] ERROR: Great spell '" + name +
                                "' not solved. Run bruteforce first!");
                        return null;
                    }
                }
                return HexPattern.fromAngles(info.signature, info.startDir);
            }
        }
        return null;
    }

    /**
     * Resolve number patterns
     */
    private static Double resolveNumberPattern(HexPattern pattern) {
        // Use the NumberPatternDecoder to check if this is a number pattern
        return NumberPatternDecoder.decodeSingleNumberPattern(pattern);
    }

    private static HexPattern createNumberPattern(double value) {
        try {
            // Use your existing NumberPatternGenerator
            com.t.hexcastingplus.client.NumberPatternGenerator.PatternSequence sequence =
                    com.t.hexcastingplus.client.NumberPatternGenerator.convertToPatternSequence(value);

            if (sequence != null && !sequence.components.isEmpty()) {
                // Return the first pattern component (the main number pattern)
                com.t.hexcastingplus.client.NumberPatternGenerator.PatternSequence.PatternComponent first =
                        sequence.components.get(0);
                return HexPattern.fromAngles(first.pattern, first.startDir);
            }
        } catch (Exception e) {
            // Fall through to fallback
        }

        // Fallback
        return HexPattern.fromAngles("aqaa", HexDir.SOUTH_EAST);
    }

    private static String resolveBookkeepersGambit(String signature, HexDir startDir) {
        if (signature.isEmpty()) {
            return "Bookkeeper's Gambit: -";
        }

        try {
            // Create a HexPattern to get the directions list
            HexPattern pattern = HexPattern.fromAngles(signature, startDir);

            // Get all directions (this includes startDir as first element)
            List<HexDir> directions = new ArrayList<>();
            directions.add(startDir);
            HexDir compass = startDir;
            for (char c : signature.toCharArray()) {
                HexAngle angle;
                switch (c) {
                    case 'w': angle = HexAngle.FORWARD; break;
                    case 'e': angle = HexAngle.RIGHT; break;
                    case 'd': angle = HexAngle.RIGHT_BACK; break;
                    case 's': angle = HexAngle.BACK; break;
                    case 'a': angle = HexAngle.LEFT_BACK; break;
                    case 'q': angle = HexAngle.LEFT; break;
                    default: return null;
                }
                compass = compass.rotatedBy(angle);
                directions.add(compass);
            }

            // Determine flat direction
            HexDir flatDir = startDir;
            if (!signature.isEmpty() && signature.charAt(0) == 'a') { // LEFT_BACK
                flatDir = startDir.rotatedBy(HexAngle.LEFT);
            }

            // Build the mask
            List<Boolean> mask = new ArrayList<>();
            int i = 0;

            while (i < directions.size()) {
                HexAngle angle = directions.get(i).angleFrom(flatDir);

                if (angle == HexAngle.FORWARD) {
                    mask.add(true);
                    i++;
                    continue;
                }

                // Check if we're at the last direction
                if (i >= directions.size() - 1) {
                    return null;
                }

                HexAngle angle2 = directions.get(i + 1).angleFrom(flatDir);
                if (angle == HexAngle.RIGHT && angle2 == HexAngle.LEFT) {
                    mask.add(false);
                    i += 2;
                    continue;
                }

                return null;
            }

            // Build visual representation
            StringBuilder visual = new StringBuilder();
            for (Boolean keep : mask) {
                visual.append(keep ? '-' : 'v');
            }

            return "Bookkeeper's Gambit: " + visual.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static HexPattern parseBookkeepersGambit(String format) {
        if (format.isEmpty()) {
            return HexPattern.fromAngles("", HexDir.EAST);
        }

        // Build angle sequence based on visual format
        StringBuilder angles = new StringBuilder();

        // Determine starting direction and first move
        char first = format.charAt(0);
        HexDir startDir;

        if (first == 'v') {
            // First 'v' needs to create a dip
            // In SOUTH_EAST orientation, 'a' creates the down part
            startDir = HexDir.SOUTH_EAST;
            angles.append('a');
        } else {
            // First '-' is a straight
            startDir = HexDir.EAST;
            // Don't add anything for first straight (pattern starts at flat)
        }

        // Process rest of pattern
        for (int i = 1; i < format.length(); i++) {
            char current = format.charAt(i);
            char previous = format.charAt(i - 1);

            if (current == '-') {
                if (previous == '-') {
                    // Continue straight - FORWARD relative to flat
                    angles.append('w');
                } else { // previous == 'v'
                    // After dip, return to straight
                    angles.append('e');
                }
            } else { // current == 'v'
                if (previous == '-') {
                    // Start a dip from straight
                    angles.append("ea");
                } else { // previous == 'v'
                    // Chain dips - this creates the zigzag
                    angles.append("da");
                }
            }
        }

        return HexPattern.fromAngles(angles.toString(), startDir);
    }
}