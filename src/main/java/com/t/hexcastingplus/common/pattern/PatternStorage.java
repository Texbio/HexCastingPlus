package com.t.hexcastingplus.common.pattern;

import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.t.hexcastingplus.client.bruteforce.BruteforceManager;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.math.BigDecimal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.world.InteractionHand;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;

public class PatternStorage {
    /**
     * Provides a safe way to call methods added by StaffGuiMixin without using reflection.
     */
    public interface StaffGuiAccess {
        void hexcastingplus$loadPattern(HexPattern pattern);
        net.minecraft.world.InteractionHand hexcastingplus$getHandUsed();
    }

    private static final String PATTERNS_FOLDER = "hexcasting_patterns";
    private static final String CONFIG_FILE = "hexcastingplus_config.json";
    private static final String FAVORITES_FILE = "favorites.json";
    private static final String PATTERN_ORDER_FILE = "pattern_order.json";
    public static final String TRASH_FOLDER = ".trash";
    public static final String CASTED_FOLDER = ".casted";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static String lastUsedFolder = "default";
    private static Config config;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Cache for favorites - key is "folder/filename" without extension
    private static Map<String, Boolean> favoritesCache = new HashMap<>();

    //Other
    private static boolean favoritesCacheLoaded = false;
    private static boolean migrationComplete = false;

    public static class SavedPattern {
        private String name;
        private List<HexPattern> patterns;
        private String folder;
        private transient boolean favorite; // transient = not saved to file
        private transient long modifiedTime = 0;

        public SavedPattern(String name, List<HexPattern> patterns, String folder) {
            this.name = name;
            this.patterns = new ArrayList<>(patterns);
            this.folder = folder != null ? folder : "default"; // Ensure folder is never null
            this.favorite = false;
        }

        // Getters
        public String getName() { return name; }
        public List<HexPattern> getPatterns() { return new ArrayList<>(patterns); }
        public String getFolder() { return folder; }

        public boolean isFavorite() {
            // Get favorite status from cache
            String key = folder + "/" + name;
            return favoritesCache.getOrDefault(key, false);
        }

        // For compatibility - these return dummy values
        public long getCreatedTime() { return 0; }
        public long getModifiedTime() {
            return modifiedTime;
        }
        public void setModifiedTime(long time) {
            this.modifiedTime = time;
        }
        public String getUuid() { return name + "_" + folder; } // Dummy UUID based on name+folder

        // Setters
        public void setName(String name) {
            this.name = name;
        }

        public void setPatterns(List<HexPattern> patterns) {
            this.patterns = new ArrayList<>(patterns);
        }

        public void setFavorite(boolean favorite) {
            // Update the favorites cache
            String key = folder + "/" + name;
            if (favorite) {
                favoritesCache.put(key, true);
            } else {
                favoritesCache.remove(key);
            }
            saveFavoritesCache();
        }

        // Should update the favorites cache when moving between folders.
        public void setFolder(String newFolder) {
            // Fix potential NPE - use current folder or "default" if null
            String currentFolder = this.folder != null ? this.folder : "default";

            // Update favorite cache key if folder changes
            String oldKey = currentFolder + "/" + this.name;
            String newKey = newFolder + "/" + this.name;
            boolean wasFavorite = favoritesCache.containsKey(oldKey);

            if (wasFavorite) {
                favoritesCache.remove(oldKey);
                favoritesCache.put(newKey, true);
                saveFavoritesCache();
            }

            this.folder = newFolder;
        }

        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            // Only save the patterns in NBT format when not using compact format
            ListTag patternList = new ListTag();
            for (HexPattern pattern : patterns) {
                patternList.add(pattern.serializeToNBT());
            }
            tag.put("patterns", patternList);
            return tag;
        }

        public static SavedPattern fromNBT(CompoundTag tag, String fileName, String folder) {

            List<HexPattern> patterns = new ArrayList<>();

            // Check if this is the old NBT format or new compact format
            if (tag.contains("patterns")) {
                ListTag patternList = tag.getList("patterns", Tag.TAG_COMPOUND);
                if (ValidationConstants.DEBUG) {System.out.println("DEBUG: Found " + patternList.size() + " patterns in NBT format");}

                for (int i = 0; i < patternList.size(); i++) {
                    patterns.add(HexPattern.fromNBT(patternList.getCompound(i)));
                }
            }

            String name = fileName;
            if (name.endsWith(".hexpattern")) {
                name = name.substring(0, name.length() - 11);
            }

            return new SavedPattern(name, patterns, folder);
        }
    }

    // Helper method to get pattern start direction
    public static HexDir getPatternStartDir(HexPattern pattern) {
        try {
            Field startDirField = HexPattern.class.getDeclaredField("startDir");
            startDirField.setAccessible(true);
            return (HexDir) startDirField.get(pattern);
        } catch (Exception e) {
            try {
                CompoundTag nbt = pattern.serializeToNBT();
                if (nbt.contains("start_dir")) {
                    byte startDirOrdinal = nbt.getByte("start_dir");
                    return HexDir.values()[startDirOrdinal];
                }
            } catch (Exception e2) {
                // Fallback
            }
            return HexDir.EAST;
        }
    }

    // ============= Simplify number patterns =============

    private static List<String> simplifyNumericalSequences(List<String> lines) {
        List<String> simplified = new ArrayList<>();
        int i = 0;

        while (i < lines.size()) {
            String line = lines.get(i).trim();

            // Skip comments and empty lines
            if (line.isEmpty() || line.startsWith("//")) {
                simplified.add(lines.get(i));
                i++;
                continue;
            }

            // Check if this starts a numerical sequence
            if (line.startsWith("Numerical Reflection:")) {
                // Look ahead to see if there's an operation following
                int sequenceEnd = i + 1;
                boolean hasOperations = false;

                // Find the end of this numerical sequence
                while (sequenceEnd < lines.size()) {
                    String nextLine = lines.get(sequenceEnd).trim();
                    if (nextLine.startsWith("Numerical Reflection:") ||
                            nextLine.equals("Additive Distillation") ||
                            nextLine.equals("Subtractive Distillation") ||
                            nextLine.equals("Multiplicative Distillation") ||
                            nextLine.equals("Division Distillation")) {
                        if (nextLine.contains("Distillation")) {
                            hasOperations = true;
                        }
                        sequenceEnd++;
                    } else {
                        break;
                    }
                }

                if (hasOperations) {
                    // Extract and evaluate the sequence
                    List<String> sequence = new ArrayList<>();
                    for (int j = i; j < sequenceEnd; j++) {
                        sequence.add(lines.get(j).trim());
                    }

                    String result = evaluateNumericalSequence(sequence);
                    if (result != null) {
                        simplified.add(result);
                        i = sequenceEnd;
                    } else {
                        // Couldn't simplify, keep original
                        simplified.add(lines.get(i));
                        i++;
                    }
                } else {
                    // No operations, just keep the number
                    simplified.add(lines.get(i));
                    i++;
                }
            } else {
                simplified.add(lines.get(i));
                i++;
            }
        }

        return simplified;
    }

    private static String evaluateNumericalSequence(List<String> sequence) {
        Stack<Double> stack = new Stack<>();

        for (String line : sequence) {
            if (line.startsWith("Numerical Reflection:")) {
                String numStr = line.substring("Numerical Reflection:".length()).trim();
                try {
                    stack.push(Double.parseDouble(numStr));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (line.equals("Additive Distillation")) {
                if (stack.size() < 2) return null;
                double b = stack.pop();
                double a = stack.pop();
                stack.push(a + b);
            } else if (line.equals("Subtractive Distillation")) {
                if (stack.size() < 2) return null;
                double b = stack.pop();
                double a = stack.pop();
                stack.push(a - b);
            } else if (line.equals("Multiplicative Distillation")) {
                if (stack.size() < 2) return null;
                double b = stack.pop();
                double a = stack.pop();

                // Check if either operand is a power of 10
                boolean aIsPowerOf10 = isPowerOfTen(a);
                boolean bIsPowerOf10 = isPowerOfTen(b);

                // Only simplify if one is a power of 10, otherwise it might create improper fraction
                if (!aIsPowerOf10 && !bIsPowerOf10) {
                    return null;
                }

                stack.push(a * b);
            } else if (line.equals("Division Distillation")) {
                if (stack.size() < 2) return null;
                double b = stack.pop();
                double a = stack.pop();

                // Check if division creates a clean result
                double result = a / b;

                // Only simplify if divisor is power of 10 OR result is a whole number
                if (!isPowerOfTen(b) && result != Math.floor(result)) {
                    return null;
                }

                stack.push(result);
            }
        }

        // Should have exactly one value left
        if (stack.size() != 1) return null;

        double result = stack.pop();

        // Format the result - FIX IS HERE
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            // Use BigDecimal to avoid scientific notation for large numbers
            BigDecimal bd = BigDecimal.valueOf(result);
            String plainString = bd.toPlainString();

            // Remove unnecessary .0 if present
            if (plainString.endsWith(".0")) {
                plainString = plainString.substring(0, plainString.length() - 2);
            }

            return "Numerical Reflection: " + plainString;
        } else {
            // For decimals, also use BigDecimal to avoid scientific notation
            BigDecimal bd = BigDecimal.valueOf(result);
            return "Numerical Reflection: " + bd.toPlainString();
        }
    }

    private static boolean isPowerOfTen(double value) {
        if (value <= 0) return false;
        double log = Math.log10(value);
        return Math.abs(log - Math.round(log)) < 0.0001;
    }

    // ============= NEW FORMAT METHODS =============

    public static void savePattern(SavedPattern pattern) {
        try {
            if (ValidationConstants.DEBUG) {System.out.println("[PatternStorage] Starting save for: " + pattern.getName());}
            String folder = ensureFolder(pattern.getFolder());
            Path folderDir = getFolderDir(folder);
            ensureDirectoryExists(folderDir);

            String fileName = sanitizeFileName(pattern.getName()) + ".hexpattern";
            Path filePath = folderDir.resolve(fileName);

            List<String> lines = new ArrayList<>();
            List<HexPattern> patterns = pattern.getPatterns();

            // Try to decode as a complete number sequence first
            Double decodedNumber = NumberPatternDecoder.decodeNumberSequence(patterns);

            if (ValidationConstants.DEBUG) {
                System.out.println("[PatternStorage] Attempting to decode " + patterns.size() + " patterns as number sequence");
                System.out.println("[PatternStorage] Decoded result: " + decodedNumber);
            }

            // Only save as consolidated number if it's a whole number or a "clean" decimal
            if (decodedNumber != null) {
                boolean isCleanDecimal = false;
                if (decodedNumber != Math.floor(decodedNumber)) {
                    // Check if it's a clean decimal (like 0.1, 0.01, not 0.555...)
                    String decimalStr = String.valueOf(decodedNumber);
                    if (decimalStr.contains(".")) {
                        String fractionalPart = decimalStr.substring(decimalStr.indexOf(".") + 1);
                        // Clean decimal if it's short and doesn't repeat
                        isCleanDecimal = fractionalPart.length() <= 3 && !fractionalPart.matches("(\\d)\\1{2,}");
                    }
                }

                if (decodedNumber == Math.floor(decodedNumber) || isCleanDecimal) {
                    // Save as single consolidated number
                    if (decodedNumber == Math.floor(decodedNumber) && !Double.isInfinite(decodedNumber)) {
                        if (decodedNumber >= Integer.MIN_VALUE && decodedNumber <= Integer.MAX_VALUE) {
                            lines.add("Numerical Reflection: " + decodedNumber.intValue());
                        } else {
                            lines.add("Numerical Reflection: " + decodedNumber.longValue());
                        }
                    } else {
                        lines.add("Numerical Reflection: " + decodedNumber);
                    }
                } else {
                    // It's a fraction - process patterns individually instead
                    decodedNumber = null; // Fall through to individual processing
                }
            }

            if (decodedNumber != null) {
                // This is a complete number sequence - save as single line
                if (decodedNumber == Math.floor(decodedNumber) && !Double.isInfinite(decodedNumber)) {
                    if (decodedNumber >= Integer.MIN_VALUE && decodedNumber <= Integer.MAX_VALUE) {
                        lines.add("Numerical Reflection: " + decodedNumber.intValue());
                    } else {
                        lines.add("Numerical Reflection: " + decodedNumber.longValue());
                    }
                } else {
                    lines.add("Numerical Reflection: " + decodedNumber);
                }
                if (ValidationConstants.DEBUG) {
                    System.out.println("[PatternStorage] Saved as consolidated number: " + decodedNumber);
                }
            } else {
                // Not a number sequence - process patterns individually
                if (ValidationConstants.DEBUG) {
                    System.out.println("[PatternStorage] Not a number sequence, processing individually");
                }

                for (HexPattern hexPattern : patterns) {
                    String resolved = PatternResolver.resolvePattern(hexPattern);
                    if (resolved != null) {
                        lines.add(resolved);
                    } else {
                        String angleSignature = hexPatternToAngleSignature(hexPattern);
                        if (angleSignature != null && !angleSignature.isEmpty()) {
                            lines.add(angleSignature);
                        } else {
                            System.out.println("[PatternStorage] ERROR: Could not save pattern!");
                        }
                    }
                }
            }

            // After building lines but before writing to file:
            lines = simplifyNumericalSequences(lines);

            Files.write(filePath, lines, StandardCharsets.UTF_8);
            if (ValidationConstants.DEBUG) {System.out.println("[PatternStorage] Save complete");}

            if (!folder.equals(TRASH_FOLDER) && !folder.equals(CASTED_FOLDER)) {
                Map<String, List<String>> orderMap = loadPatternOrder();
                List<String> order = orderMap.getOrDefault(folder, new ArrayList<>());

                if (!order.contains(pattern.getName())) {
                    order.add(pattern.getName());
                    orderMap.put(folder, order);
                    savePatternOrder(orderMap);
                }
            }

        } catch (Exception e) {
            System.out.println("[PatternStorage] Save failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void savePattern(String name, List<HexPattern> patterns, String folder) {
        // Ensure unique name when creating new pattern
        String uniqueName = ensureUniqueFileName(name, folder);
        SavedPattern savedPattern = new SavedPattern(uniqueName, patterns, folder);
        savePattern(savedPattern);
    }

    public static List<SavedPattern> getSavedPatterns(String folder) {
        // try once per game load
        if (!migrationComplete) {
            migrateOldPatternFiles();
            migrationComplete = true;
        }

        // Clean up stale entries from pattern order for this folder
        cleanupPatternOrder();

        List<SavedPattern> patterns = new ArrayList<>();

        // Ensure default folder always exists
        if (folder.equals("default")) {
            ensureDirectoryExists(getFolderDir("default"));
        }

        try {
            Path folderDir = getFolderDir(folder);
            if (!Files.exists(folderDir)) {
                return patterns;
            }

            Files.list(folderDir)
                    .filter(path -> path.toString().endsWith(".hexpattern"))
                    .forEach(path -> {
                        try {
                            List<String> lines = Files.readAllLines(path);
                            List<HexPattern> loadedPatterns = new ArrayList<>();

                            for (String line : lines) {
                                String trimmed = line.trim();

                                // Skip empty lines and comments
                                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                                    continue;
                                }

                                // Parse the line
                                List<HexPattern> parsedPatterns = PatternResolver.parsePattern(trimmed);
                                if (parsedPatterns != null) {
                                    loadedPatterns.addAll(parsedPatterns);
                                } else {
                                    // Try fallback angle signature format
                                    HexPattern pattern = angleSignatureToHexPattern(trimmed);
                                    if (pattern != null) {
                                        loadedPatterns.add(pattern);
                                    } else {
                                        System.out.println("[PatternStorage] Failed to parse line: " + trimmed);
                                    }
                                }
                            }

                            if (!loadedPatterns.isEmpty()) {
                                String fileName = path.getFileName().toString();
                                String name = fileName.endsWith(".hexpattern") ?
                                        fileName.substring(0, fileName.length() - 11) : fileName;

                                SavedPattern savedPattern = new SavedPattern(name, loadedPatterns, folder);

                                if (folder.equals(TRASH_FOLDER) || folder.equals(CASTED_FOLDER)) {
                                    try {
                                        long modTime = Files.getLastModifiedTime(path).toMillis();
                                        savedPattern.setModifiedTime(modTime);
                                    } catch (IOException e) {
                                        // If we can't get the time, leave it as 0
                                    }
                                }

                                patterns.add(savedPattern);
                            }
                        } catch (Exception e) {
                            if (ValidationConstants.DEBUG_ERROR) {System.out.println("DEBUG: Error reading pattern file: " + path.getFileName() + " - " + e.getMessage());}
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Ensure all loaded patterns are in the pattern order
        if (!folder.equals(TRASH_FOLDER) && !folder.equals(CASTED_FOLDER)) {
            Map<String, List<String>> orderMap = loadPatternOrder();
            List<String> order = orderMap.getOrDefault(folder, new ArrayList<>());
            boolean changed = false;

            for (SavedPattern p : patterns) {
                if (!order.contains(p.getName())) {
                    order.add(p.getName());
                    changed = true;
                }
            }

            if (changed) {
                orderMap.put(folder, order);
                savePatternOrder(orderMap);
            }
        }

        return patterns.stream()
                .sorted((a, b) -> {
                    // Sort favorites first, then by name
                    if (a.isFavorite() != b.isFavorite()) {
                        return Boolean.compare(b.isFavorite(), a.isFavorite());
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                })
                .collect(Collectors.toList());
    }

    // ============= MIGRATION =============

    private static void migrateOldPatternFiles() {
        PatternResolver.initializeRegistry();
        BruteforceManager.getInstance().ensureCacheLoaded();

        try {
            migrateFolder(getPatternsDir());
            if (ValidationConstants.DEBUG) {System.out.println("[PatternStorage] Migration complete!");}
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {System.out.println("[PatternStorage] Migration failed: " + e.getMessage());}
            e.printStackTrace();
        }
    }

    private static void migrateFolder(Path folderPath) throws IOException {
        if (!Files.exists(folderPath)) return;

        // Process all .hexpattern files in this folder
        try (Stream<Path> paths = Files.list(folderPath)) {
            paths.filter(path -> path.toString().endsWith(".hexpattern"))
                    .forEach(PatternStorage::migrateFile);
        }

        // Process subfolders
        try (Stream<Path> paths = Files.list(folderPath)) {
            paths.filter(Files::isDirectory)
                    .forEach(subfolder -> {
                        try {
                            migrateFolder(subfolder);
                        } catch (IOException e) {
                            if (ValidationConstants.DEBUG_ERROR) {System.out.println("[PatternStorage] Failed to migrate folder: " + subfolder);}
                            e.printStackTrace();
                        }
                    });
        }
    }

    private static void migrateFile(Path filePath) {
        if (ValidationConstants.DEBUG) {System.out.println("[PatternStorage] Migrating: " + filePath);}

        try {
            List<String> lines = Files.readAllLines(filePath);
            List<String> newLines = new ArrayList<>();
            boolean needsMigration = false;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    newLines.add(line);
                    continue;
                }

                // Remove inline comments before processing
                int commentIndex = trimmed.indexOf("//");
                String processLine = trimmed;
                if (commentIndex > 0) {
                    processLine = trimmed.substring(0, commentIndex).trim();
                }

                if (!processLine.contains(",")) {
                    newLines.add(line);
                    continue;
                }

                String[] parts = processLine.split(",", 2);
                if (parts.length == 2) {
                    String angleSignature = parts[0];
                    String dirName = parts[1];

                    try {
                        HexDir startDir = HexDir.valueOf(dirName);
                        String resolved = PatternResolver.resolveFromAngleSignature(angleSignature, startDir);

                        if (resolved != null) {
                            newLines.add(resolved);
                            needsMigration = true;
                            if (ValidationConstants.DEBUG) {System.out.println("  Converted: " + trimmed + " -> " + resolved);}
                        } else {
                            newLines.add(trimmed);
                            if (ValidationConstants.DEBUG_ERROR) {System.out.println("  WARNING: Couldn't resolve pattern: " + trimmed);}
                        }
                    } catch (Exception e) {
                        newLines.add(line);
                        if (ValidationConstants.DEBUG_ERROR) {System.out.println("  WARNING: Invalid direction: " + dirName);}
                    }
                } else {
                    newLines.add(line);
                }
            }

            if (needsMigration) {
                Files.write(filePath, newLines, StandardCharsets.UTF_8);
                if (ValidationConstants.DEBUG) {System.out.println("  File updated successfully");}
            }
        } catch (Exception e) {
            System.out.println("[PatternStorage] Failed to migrate file: " + filePath);
            System.out.println("  Error: " + e.getMessage());
        }
    }

    private static String hexPatternToAngleSignature(HexPattern pattern) {
        try {
            String signature = pattern.anglesSignature();
            HexDir startDir = getPatternStartDir(pattern);
            return signature + "," + startDir.name();
        } catch (Exception e) {
                if (ValidationConstants.DEBUG_ERROR) {System.out.println("Error converting pattern to angle signature: " + e.getMessage());}
            return null;
        }
    }

    /**
     * Loads HexPattern from angle signature format
     * Input: "qaq,WEST"
     * Output: HexPattern object
     */
    public static HexPattern angleSignatureToHexPattern(String line) {
        if (line == null) return null;

        // Remove comments first
        int commentIndex = line.indexOf("//");
        if (commentIndex >= 0) {
            line = line.substring(0, commentIndex).trim();
        }

        if (!line.contains(",")) return null;

        String[] parts = line.split(",", 2);
        if (parts.length != 2) return null;

        String angleSignature = parts[0];
        String dirName = parts[1];

        try {
            HexDir startDir = HexDir.valueOf(dirName);
            return HexPattern.fromAngles(angleSignature, startDir);
        } catch (Exception e) {
            return null;
        }
    }

    // ============ REST OF THE CLASS REMAINS THE SAME ============

    private static class Config {
        public String lastUsedFolder = "default";
        public Map<String, Object> settings = new HashMap<>();
    }

    static {
        loadConfig();
        loadFavoritesCache();
    }


    private static void loadFavoritesCache() {
        try {
            Path favoritesPath = getPatternsDir().resolve(FAVORITES_FILE);
            if (Files.exists(favoritesPath)) {
                String json = Files.readString(favoritesPath);
                Type type = new TypeToken<Map<String, Boolean>>(){}.getType();
                Map<String, Boolean> loaded = GSON.fromJson(json, type);
                if (loaded != null) {
                    favoritesCache = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        favoritesCacheLoaded = true;

        // Clean up favorites cache - remove entries for files that don't exist
        cleanupFavoritesCache();
    }

    public static void saveFavoritesCache() {
        try {
            // Clean up before saving
            cleanupFavoritesCache();

            Path favoritesPath = getPatternsDir().resolve(FAVORITES_FILE);
            ensureDirectoryExists(getPatternsDir());
            Files.writeString(favoritesPath, GSON.toJson(favoritesCache));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void cleanupFavoritesCache() {
        List<String> toRemove = new ArrayList<>();
        for (String key : favoritesCache.keySet()) {
            String[] parts = key.split("/", 2);
            if (parts.length == 2) {
                String folder = parts[0];
                String name = parts[1];
                Path filePath = getFolderDir(folder).resolve(sanitizeFileName(name) + ".hexpattern");
                if (!Files.exists(filePath)) {
                    toRemove.add(key);
                }
            }
        }
        for (String key : toRemove) {
            favoritesCache.remove(key);
        }
        if (!toRemove.isEmpty()) {
            saveFavoritesCache();
        }
    }

    private static void cleanupPatternOrder() {
        Map<String, List<String>> orderMap = loadPatternOrder();
        boolean changed = false;

        // Clean up non-existent files from folders
        for (Map.Entry<String, List<String>> entry : orderMap.entrySet()) {
            String folder = entry.getKey();
            List<String> order = entry.getValue();
            List<String> toRemove = new ArrayList<>();

            for (String patternName : order) {
                Path filePath = getFolderDir(folder).resolve(sanitizeFileName(patternName) + ".hexpattern");
                if (!Files.exists(filePath)) {
                    toRemove.add(patternName);
                    changed = true;
                }
            }

            order.removeAll(toRemove);
            // Keep the folder in the map even if empty - don't remove it
        }

        if (changed) {
            savePatternOrder(orderMap);
        }
    }

    public static Map<String, List<String>> loadPatternOrder() {
        Map<String, List<String>> order = new HashMap<>();
        try {
            Path orderPath = getPatternsDir().resolve(PATTERN_ORDER_FILE);
            if (Files.exists(orderPath)) {
                String json = Files.readString(orderPath);
                Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
                Map<String, List<String>> loaded = GSON.fromJson(json, type);
                if (loaded != null) {
                    order = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return order;
    }

    public static void savePatternOrder(Map<String, List<String>> patternOrder) {
        try {
            Path orderPath = getPatternsDir().resolve(PATTERN_ORDER_FILE);
            ensureDirectoryExists(getPatternsDir());
            Files.writeString(orderPath, GSON.toJson(patternOrder));
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {System.out.println("DEBUG: Failed to save pattern order!");}
            e.printStackTrace();
        }
    }

    // PatternEditScreen Methods


    public static class PatternLine {
        private final String rawLine;
        private final HexPattern pattern; // null if not a pattern line
        private final int lineNumber;

        public PatternLine(String rawLine, HexPattern pattern, int lineNumber) {
            this.rawLine = rawLine;
            this.pattern = pattern;
            this.lineNumber = lineNumber;
        }

        public boolean isPattern() { return pattern != null; }
        public String getRawLine() { return rawLine; }
        public HexPattern getPattern() { return pattern; }
        public int getLineNumber() { return lineNumber; }
    }

    public static List<PatternLine> loadPatternFileWithComments(String patternName, String folder) {
        List<PatternLine> lines = new ArrayList<>();
        Path filePath = getFolderDir(folder).resolve(sanitizeFileName(patternName) + ".hexpattern");

        try {
            List<String> rawLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            for (int i = 0; i < rawLines.size(); i++) {
                String line = rawLines.get(i);
                String trimmed = line.trim();

                HexPattern pattern = null;

                // Skip empty lines and pure comments
                if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                    // Check if line has an inline comment
                    String patternPart = line;
                    if (line.contains("//")) {
                        patternPart = line.substring(0, line.indexOf("//")).trim();
                    }

                    // Try to parse as pattern
                    List<HexPattern> parsed = PatternResolver.parsePattern(patternPart);
                    if (parsed != null && !parsed.isEmpty()) {
                        pattern = parsed.get(0);
                    } else {
                        // Try fallback angle signature format
                        pattern = angleSignatureToHexPattern(patternPart);
                    }
                }

                lines.add(new PatternLine(line, pattern, i));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return lines;
    }

    public static boolean updatePatternFile(String patternName, String folder, List<String> newLines) {
        try {
            Path filePath = getFolderDir(folder).resolve(sanitizeFileName(patternName) + ".hexpattern");

            // Preserve file timestamps
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            FileTime creationTime = attrs.creationTime();
            FileTime lastAccessTime = attrs.lastAccessTime();

            // Write the new content
            Files.write(filePath, newLines, StandardCharsets.UTF_8);

            // Restore creation and access times (but keep new modified time)
            BasicFileAttributeView attributeView = Files.getFileAttributeView(filePath, BasicFileAttributeView.class);
            if (attributeView != null) {
                attributeView.setTimes(null, lastAccessTime, creationTime);
            }

            return true;
        } catch (IOException e) {
            System.out.println("Failed to update pattern file: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ============================

    private static void loadConfig() {
        try {
            Path configPath = getPatternsDir().resolve(CONFIG_FILE);
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                config = GSON.fromJson(json, Config.class);
                if (config != null) {
                    lastUsedFolder = config.lastUsedFolder;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (config == null) {
            config = new Config();
        }
    }

    private static void saveConfig() {
        try {
            config.lastUsedFolder = lastUsedFolder;
            Path configPath = getPatternsDir().resolve(CONFIG_FILE);
            ensureDirectoryExists(getPatternsDir());
            Files.writeString(configPath, GSON.toJson(config));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getLastUsedFolder() {
        return lastUsedFolder;
    }

    public static void setLastUsedFolder(String folder) {
        lastUsedFolder = folder;
        saveConfig();
    }

    private static Path getMinecraftDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    private static Path getPatternsDir() {
        return getMinecraftDir().resolve(PATTERNS_FOLDER);
    }

    private static Path getFolderDir(String folder) {
        return getPatternsDir().resolve(folder);
    }

    private static void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<String> getAvailableFolders() {
        List<String> folders = new ArrayList<>();

        // Always ensure default folder exists and is first
        Path defaultFolderPath = getFolderDir("default");
        ensureDirectoryExists(defaultFolderPath);
        folders.add("default");

        Path patternsDir = getPatternsDir();
        if (Files.exists(patternsDir)) {
            // Use try-with-resources to ensure stream is closed
            try (var stream = Files.list(patternsDir)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> !name.equals("default") && !name.startsWith("."))
                        .sorted()
                        .forEach(folders::add);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return folders;
    }

    public static void createFolder(String folderName) {
        if (folderName == null || folderName.trim().isEmpty() || folderName.startsWith(".")) {
            return;
        }

        Path folderPath = getFolderDir(folderName);
        ensureDirectoryExists(folderPath);
    }

    public static void deleteFolder(String folderName) {
        if (folderName == null || folderName.trim().isEmpty() ||
                folderName.equals("default") || folderName.startsWith(".")) {
            return;
        }

        try {
            Path folderPath = getFolderDir(folderName);
            if (Files.exists(folderPath)) {
                // Move all patterns to trash
                List<SavedPattern> patterns = getSavedPatterns(folderName);
                for (SavedPattern pattern : patterns) {
                    movePattern(pattern, TRASH_FOLDER, null);
                }

                // Delete the empty folder
                Files.delete(folderPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void renameFolder(String oldFolderName, String newFolderName) {
        if (oldFolderName == null || newFolderName == null ||
                oldFolderName.trim().isEmpty() || newFolderName.trim().isEmpty() ||
                oldFolderName.equals("default") || oldFolderName.startsWith(".") ||
                newFolderName.startsWith(".") || oldFolderName.equals(newFolderName)) {
            return;
        }

        // Store original state for rollback
        Map<String, Boolean> originalFavoritesCache = new HashMap<>(favoritesCache);
        Map<String, List<String>> originalOrderMap = loadPatternOrder();
        String originalLastUsedFolder = lastUsedFolder;

        try {
            Path oldPath = getFolderDir(oldFolderName);
            Path newPath = getFolderDir(newFolderName);

            if (Files.exists(oldPath) && !Files.exists(newPath)) {
                // Move the entire folder (preserves file creation dates)
                Files.move(oldPath, newPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // Update favorites cache keys for all patterns in the folder
                updateFavoritesCacheForFolderRename(oldFolderName, newFolderName);

                // Update pattern order if exists
                Map<String, List<String>> orderMap = loadPatternOrder();
                if (orderMap.containsKey(oldFolderName)) {
                    List<String> order = orderMap.remove(oldFolderName);
                    orderMap.put(newFolderName, order);
                    savePatternOrder(orderMap);
                }

                // If this was the last used folder, update it
                if (lastUsedFolder.equals(oldFolderName)) {
                    setLastUsedFolder(newFolderName);
                }
            }
        } catch (IOException e) {
            // Rollback changes if move failed
            System.out.println("Failed to rename folder, attempting rollback: " + e.getMessage());
            e.printStackTrace();

            // Try to restore original state
            try {
                // Check if we need to move the folder back
                Path oldPath = getFolderDir(oldFolderName);
                Path newPath = getFolderDir(newFolderName);
                if (!Files.exists(oldPath) && Files.exists(newPath)) {
                    Files.move(newPath, oldPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                // Restore caches
                favoritesCache = originalFavoritesCache;
                saveFavoritesCache();
                savePatternOrder(originalOrderMap);

                // Restore last used folder
                if (!lastUsedFolder.equals(originalLastUsedFolder)) {
                    setLastUsedFolder(originalLastUsedFolder);
                }
            } catch (IOException rollbackError) {
                System.out.println("Failed to rollback folder rename: " + rollbackError.getMessage());
                rollbackError.printStackTrace();
            }
        }
    }

    private static void updateFavoritesCacheForFolderRename(String oldFolder, String newFolder) {
        Map<String, Boolean> newCache = new HashMap<>();
        for (Map.Entry<String, Boolean> entry : favoritesCache.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(oldFolder + "/")) {
                String newKey = newFolder + key.substring(oldFolder.length());
                newCache.put(newKey, entry.getValue());
            } else {
                newCache.put(key, entry.getValue());
            }
        }
        favoritesCache = newCache;
        saveFavoritesCache();
    }

    public static List<SavedPattern> getTrashedPatterns() {
        return getSavedPatterns(TRASH_FOLDER);
    }

    public static List<SavedPattern> getCastedPatterns() {
        return getSavedPatterns(CASTED_FOLDER);
    }

    public static String ensureUniqueFileName(String baseName, String folder) {
        Path folderDir = getFolderDir(folder);
        String sanitizedBase = sanitizeFileName(baseName);
        Path filePath = folderDir.resolve(sanitizedBase + ".hexpattern");

        // If the exact name doesn't exist, use it
        if (!Files.exists(filePath)) {
            return baseName;
        }

        // Find the smallest available number suffix
        int counter = 2;
        while (true) {
            String newName = baseName + "_" + counter;
            Path newPath = folderDir.resolve(sanitizeFileName(newName) + ".hexpattern");

            if (!Files.exists(newPath)) {
                return newName;
            }

            counter++;
            if (counter > 9999) {
                return baseName + "_" + System.currentTimeMillis();
            }
        }
    }

    private static String ensureFolder(String folder) {
        return folder != null ? folder : "default";
    }

    public static String renamePatternFile(SavedPattern pattern, String newName) {
        if (pattern == null || newName == null || newName.trim().isEmpty()) {
            return null;
        }

        String oldName = pattern.getName();
        String folder = ensureFolder(pattern.getFolder());

        // If the name hasn't changed, return immediately
        if (oldName.equals(newName)) {
            return oldName;
        }

        Path folderDir = getFolderDir(folder);
        Path oldPath = folderDir.resolve(sanitizeFileName(oldName) + ".hexpattern");

        // Check if file exists
        if (!Files.exists(oldPath)) {
            System.out.println("Pattern file not found: " + oldPath);
            return null;
        }

        // Determine the final name
        String finalName;
        boolean isCaseChangeOnly = oldName.equalsIgnoreCase(newName);

        if (isCaseChangeOnly) {
            // Case-only change, keep the same name
            finalName = newName;
        } else {
            // Find unique name, excluding our current file from the check
            finalName = findUniqueNameExcluding(newName, folder, oldName);
        }

        Path newPath = folderDir.resolve(sanitizeFileName(finalName) + ".hexpattern");

        try {
            if (isCaseChangeOnly) {
                // For case-only changes on Windows, we need to delete and recreate
                try {
                    // Store the original file attributes
                    BasicFileAttributes attrs = Files.readAttributes(oldPath, BasicFileAttributes.class);
                    FileTime creationTime = attrs.creationTime();
                    FileTime lastModifiedTime = attrs.lastModifiedTime();
                    FileTime lastAccessTime = attrs.lastAccessTime();

                    // Read the file content
                    byte[] content = Files.readAllBytes(oldPath);

                    // Delete the old file
                    Files.delete(oldPath);

                    // Create the new file with the new case
                    Files.write(newPath, content);

                    // Restore the file times
                    BasicFileAttributeView attributeView = Files.getFileAttributeView(newPath, BasicFileAttributeView.class);
                    if (attributeView != null) {
                        attributeView.setTimes(lastModifiedTime, lastAccessTime, creationTime);
                    }
                } catch (IOException e) {
                    System.out.println("Case rename failed: " + e.getMessage());
                    throw e;
                }
            } else if (!oldPath.equals(newPath)) {
                // Only move if paths are different
                Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Only update caches/order if name actually changed
            if (!oldName.equals(finalName)) {
                // Update favorites cache if the pattern was favorited
                boolean wasFavorite = pattern.isFavorite();
                if (wasFavorite) {
                    String oldKey = folder + "/" + oldName;
                    String newKey = folder + "/" + finalName;
                    favoritesCache.remove(oldKey);
                    favoritesCache.put(newKey, true);
                    saveFavoritesCache();
                }

                // Update pattern order - KEEP THE SAME POSITION
                Map<String, List<String>> orderMap = loadPatternOrder();
                List<String> order = orderMap.getOrDefault(folder, new ArrayList<>());

                int position = order.indexOf(oldName);
                if (position >= 0) {
                    order.set(position, finalName);
                } else {
                    // Add based on folder type if not found
                    if (folder.equals(TRASH_FOLDER) || folder.equals(CASTED_FOLDER)) {
                        int insertPos = 0;
                        List<SavedPattern> allPatterns = getSavedPatterns(folder);
                        for (SavedPattern p : allPatterns) {
                            if (p.isFavorite() && !p.getName().equals(finalName)) {
                                insertPos++;
                            }
                        }
                        order.add(insertPos, finalName);
                    } else {
                        order.add(finalName);
                    }
                }

                orderMap.put(folder, order);
                savePatternOrder(orderMap);
            }

            // Update the pattern object itself with the final name
            pattern.setName(finalName);
            return finalName;

        } catch (IOException e) {
            System.out.println("Failed to rename pattern file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static boolean copyPatternFile(String sourceName, String destName, String folder) {
        try {
            Path folderDir = getFolderDir(folder);
            Path sourcePath = folderDir.resolve(sanitizeFileName(sourceName) + ".hexpattern");
            Path destPath = folderDir.resolve(sanitizeFileName(destName) + ".hexpattern");

            if (!Files.exists(sourcePath)) {
                System.out.println("Source pattern file not found: " + sourcePath);
                return false;
            }

            // Copy the file byte-for-byte, preserving all content including comments
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);

            // If the source was favorited, don't copy the favorite status
            // (new duplicates should start unfavorited)

            return true;
        } catch (IOException e) {
            System.out.println("Failed to copy pattern file: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static String findUniqueNameExcluding(String baseName, String folder, String excludeName) {
        Path folderDir = getFolderDir(folder);
        String sanitizedBase = sanitizeFileName(baseName);
        String sanitizedExclude = sanitizeFileName(excludeName);

        // Check if base name is available (excluding our current file)
        Path basePath = folderDir.resolve(sanitizedBase + ".hexpattern");
        Path excludePath = folderDir.resolve(sanitizedExclude + ".hexpattern");

        if (!Files.exists(basePath) || basePath.equals(excludePath)) {
            return baseName;
        }

        // Find the smallest available number suffix
        int counter = 2;
        while (true) {
            String newName = baseName + "_" + counter;
            Path newPath = folderDir.resolve(sanitizeFileName(newName) + ".hexpattern");

            // This name is available if it doesn't exist OR if it's our current file
            if (!Files.exists(newPath) || newPath.equals(excludePath)) {
                return newName;
            }

            counter++;
            if (counter > 9999) {
                return baseName + "_" + System.currentTimeMillis();
            }
        }
    }

    public static void restoreFromTrash(SavedPattern pattern, String targetFolder, Integer insertionIndex) {
        try {
            String oldName = pattern.getName();
            Path trashPath = getFolderDir(TRASH_FOLDER).resolve(sanitizeFileName(oldName) + ".hexpattern");

            // Ensure target folder exists
            ensureDirectoryExists(getFolderDir(targetFolder));

            // Ensure unique name in target folder
            String uniqueName = ensureUniqueFileName(pattern.getName(), targetFolder);
            Path targetPath = getFolderDir(targetFolder).resolve(sanitizeFileName(uniqueName) + ".hexpattern");

            if (Files.exists(trashPath)) {
                // Special handling for TRASH and CASTED targets
                if (targetFolder.equals(TRASH_FOLDER) || targetFolder.equals(CASTED_FOLDER)) {
                    // Count favorites BEFORE moving the file
                    List<SavedPattern> targetPatterns;
                    if (targetFolder.equals(TRASH_FOLDER)) {
                        targetPatterns = getTrashedPatterns();
                    } else {
                        targetPatterns = getCastedPatterns();
                    }

                    int favoriteCount = 0;
                    for (SavedPattern p : targetPatterns) {
                        if (p.isFavorite()) {
                            favoriteCount++;
                        }
                    }

                    // NOW move the file
                    Files.move(trashPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // Update favorites cache
                    String oldKey = TRASH_FOLDER + "/" + oldName;
                    if (favoritesCache.containsKey(oldKey)) {
                        String newKey = targetFolder + "/" + uniqueName;
                        favoritesCache.put(newKey, favoritesCache.remove(oldKey));
                        saveFavoritesCache();
                    }

                    // Update pattern object
                    pattern.setName(uniqueName);
                    pattern.setFolder(targetFolder);

                    // Update pattern order
                    Map<String, List<String>> orderMap = loadPatternOrder();

                    // Remove from trash order
                    List<String> trashOrder = orderMap.getOrDefault(TRASH_FOLDER, new ArrayList<>());
                    trashOrder.remove(oldName);
                    orderMap.put(TRASH_FOLDER, trashOrder);

                    // Add to target order
                    List<String> order = new ArrayList<>(orderMap.getOrDefault(targetFolder, new ArrayList<>()));
                    order.remove(uniqueName);

                    if (insertionIndex != null && insertionIndex >= 0) {
                        boolean isDraggedFavorite = pattern.isFavorite();
                        int adjustedIndex = insertionIndex;

                        if (!isDraggedFavorite && insertionIndex < favoriteCount) {
                            adjustedIndex = favoriteCount;
                        }

                        int insertAt = Math.min(adjustedIndex, order.size());
                        order.add(insertAt, uniqueName);
                    } else {
                        order.add(favoriteCount, uniqueName);
                    }

                    orderMap.put(targetFolder, order);
                    savePatternOrder(orderMap);
                } else {
                    // Regular folder target - existing logic
                    Files.move(trashPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // Update favorites cache
                    String oldKey = TRASH_FOLDER + "/" + oldName;
                    if (favoritesCache.containsKey(oldKey)) {
                        String newKey = targetFolder + "/" + uniqueName;
                        favoritesCache.put(newKey, favoritesCache.remove(oldKey));
                        saveFavoritesCache();
                    }

                    // Update pattern object
                    pattern.setName(uniqueName);
                    pattern.setFolder(targetFolder);

                    // Update pattern order for regular folders
                    Map<String, List<String>> orderMap = loadPatternOrder();
                    List<String> order = new ArrayList<>(orderMap.getOrDefault(targetFolder, new ArrayList<>()));

                    order.remove(uniqueName);
                    if (insertionIndex != null && insertionIndex >= 0) {
                        int insertAt = Math.min(insertionIndex, order.size());
                        order.add(insertAt, uniqueName);
                    } else {
                        order.add(uniqueName);
                    }

                    orderMap.put(targetFolder, order);
                    savePatternOrder(orderMap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void permanentlyDeletePattern(SavedPattern pattern) {
        deletePattern(pattern);
    }

    public static void deletePattern(SavedPattern pattern) {
        try {
            String folder = ensureFolder(pattern.getFolder());
            Path folderDir = getFolderDir(folder);
            String fileName = sanitizeFileName(pattern.getName()) + ".hexpattern";
            Path filePath = folderDir.resolve(fileName);

            if (Files.exists(filePath)) {
                Files.delete(filePath);

                // Remove from favorites if it was favorited
                String key = folder + "/" + pattern.getName();
                if (favoritesCache.remove(key) != null) {
                    saveFavoritesCache();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void movePattern(SavedPattern pattern, String targetFolder, Integer insertionIndex) {
        if (ValidationConstants.DEBUG) {
            System.out.println("DEBUG: movePattern called - from: " + pattern.getFolder() + " to: " + targetFolder);
            System.out.println("DEBUG: Pattern: " + pattern.getName() + ", insertionIndex: " + insertionIndex);
        }

        try {
            String oldFolder = pattern.getFolder();
            String oldName = pattern.getName();

            // Handle special source folders
            if (oldFolder.equals(CASTED_FOLDER)) {
                restoreFromCasted(pattern, targetFolder, insertionIndex);
                return;
            } else if (oldFolder.equals(TRASH_FOLDER)) {
                restoreFromTrash(pattern, targetFolder, insertionIndex);
                return;
            }

            Path sourcePath = getFolderDir(oldFolder).resolve(sanitizeFileName(oldName) + ".hexpattern");
            ensureDirectoryExists(getFolderDir(targetFolder));

            String uniqueName = ensureUniqueFileName(pattern.getName(), targetFolder);
            Path destPath = getFolderDir(targetFolder).resolve(sanitizeFileName(uniqueName) + ".hexpattern");

            if (Files.exists(sourcePath)) {
                // Move the file
                Files.move(sourcePath, destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // Update favorites cache
                String oldKey = oldFolder + "/" + oldName;
                String newKey = targetFolder + "/" + uniqueName;
                if (!oldKey.equals(newKey) && favoritesCache.containsKey(oldKey)) {
                    favoritesCache.put(newKey, favoritesCache.remove(oldKey));
                    saveFavoritesCache();
                }

                // Update pattern object
                pattern.setName(uniqueName);
                pattern.setFolder(targetFolder);

                // Update pattern order
                Map<String, List<String>> orderMap = loadPatternOrder();

                // Don't remove from old folder order - keep the position remembered
                // Ghost entries for moved patterns are harmless and preserve position if moved back

                // Build complete order for target folder
                // Get the current order for the target folder (may contain ghost entries)
                List<String> existingOrder = orderMap.getOrDefault(targetFolder, new ArrayList<>());

                // Get actual files in the target folder
                List<SavedPattern> targetPatterns = getSavedPatterns(targetFolder);
                Set<String> actualPatternNames = new HashSet<>();
                for (SavedPattern p : targetPatterns) {
                    actualPatternNames.add(p.getName());
                }

                // Build clean order from existing order, removing ghost entries
                List<String> completeOrder = new ArrayList<>();
                for (String name : existingOrder) {
                    if (actualPatternNames.contains(name) && !name.equals(uniqueName)) {
                        completeOrder.add(name);
                    }
                }

                // Add any actual patterns not in the order (manually added files)
                for (SavedPattern p : targetPatterns) {
                    if (!existingOrder.contains(p.getName()) && !p.getName().equals(uniqueName)) {
                        completeOrder.add(p.getName());
                    }
                }

                // Now insert at the correct visual position
                if (insertionIndex != null && insertionIndex >= 0) {
                    int insertAt = Math.min(insertionIndex, completeOrder.size());
                    completeOrder.add(insertAt, uniqueName);
                } else {
                    // For TRASH and CASTED, new items go to top (after favorites)
                    if (targetFolder.equals(TRASH_FOLDER) || targetFolder.equals(CASTED_FOLDER)) {
                        // Count favorites in the completed order
                        int favoriteCount = 0;
                        for (String name : completeOrder) {
                            String key = targetFolder + "/" + name;
                            if (favoritesCache.getOrDefault(key, false)) {
                                favoriteCount++;
                            }
                        }
                        completeOrder.add(favoriteCount, uniqueName);
                    } else {
                        // Regular folders: new items at bottom
                        completeOrder.add(uniqueName);
                    }
                }

                orderMap.put(targetFolder, completeOrder);
                savePatternOrder(orderMap);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void deletePattern(SavedPattern pattern, String folder) {
        try {
            Path folderDir = getFolderDir(folder);
            String fileName = sanitizeFileName(pattern.getName()) + ".hexpattern";
            Path filePath = folderDir.resolve(fileName);

            if (Files.exists(filePath)) {
                Files.delete(filePath);

                // Remove from favorites if it was favorited
                String key = folder + "/" + pattern.getName();
                if (favoritesCache.remove(key) != null) {
                    saveFavoritesCache();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void restoreFromCasted(SavedPattern pattern, String targetFolder, Integer insertionIndex) {
        try {
            String oldName = pattern.getName();
            Path castedPath = getFolderDir(CASTED_FOLDER).resolve(sanitizeFileName(oldName) + ".hexpattern");

            // Ensure target folder exists
            ensureDirectoryExists(getFolderDir(targetFolder));

            // Ensure unique name in target folder
            String uniqueName = ensureUniqueFileName(pattern.getName(), targetFolder);
            Path targetPath = getFolderDir(targetFolder).resolve(sanitizeFileName(uniqueName) + ".hexpattern");

            if (Files.exists(castedPath)) {
                // Special handling for TRASH and CASTED targets
                if (targetFolder.equals(TRASH_FOLDER) || targetFolder.equals(CASTED_FOLDER)) {
                    // Count favorites BEFORE moving the file
                    List<SavedPattern> targetPatterns;
                    if (targetFolder.equals(TRASH_FOLDER)) {
                        targetPatterns = getTrashedPatterns();
                    } else {
                        targetPatterns = getCastedPatterns();
                    }

                    int favoriteCount = 0;
                    for (SavedPattern p : targetPatterns) {
                        if (p.isFavorite()) {
                            favoriteCount++;
                        }
                    }

                    // NOW move the file
                    Files.move(castedPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // Update favorites cache
                    String oldKey = CASTED_FOLDER + "/" + oldName;
                    if (favoritesCache.containsKey(oldKey)) {
                        String newKey = targetFolder + "/" + uniqueName;
                        favoritesCache.put(newKey, favoritesCache.remove(oldKey));
                        saveFavoritesCache();
                    }

                    // Update pattern object
                    pattern.setName(uniqueName);
                    pattern.setFolder(targetFolder);

                    // Update pattern order
                    Map<String, List<String>> orderMap = loadPatternOrder();

                    // Remove from casted order
                    List<String> castedOrder = orderMap.getOrDefault(CASTED_FOLDER, new ArrayList<>());
                    castedOrder.remove(oldName);
                    orderMap.put(CASTED_FOLDER, castedOrder);

                    // Add to target order
                    List<String> order = new ArrayList<>(orderMap.getOrDefault(targetFolder, new ArrayList<>()));
                    order.remove(uniqueName);

                    if (insertionIndex != null && insertionIndex >= 0) {
                        boolean isDraggedFavorite = pattern.isFavorite();
                        int adjustedIndex = insertionIndex;

                        if (!isDraggedFavorite && insertionIndex < favoriteCount) {
                            adjustedIndex = favoriteCount;
                        }

                        int insertAt = Math.min(adjustedIndex, order.size());
                        order.add(insertAt, uniqueName);
                    } else {
                        order.add(favoriteCount, uniqueName);
                    }

                    orderMap.put(targetFolder, order);
                    savePatternOrder(orderMap);
                } else {
                    // Regular folder target - existing logic
                    Files.move(castedPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // Update favorites cache
                    String oldKey = CASTED_FOLDER + "/" + oldName;
                    if (favoritesCache.containsKey(oldKey)) {
                        String newKey = targetFolder + "/" + uniqueName;
                        favoritesCache.put(newKey, favoritesCache.remove(oldKey));
                        saveFavoritesCache();
                    }

                    // Update pattern object
                    pattern.setName(uniqueName);
                    pattern.setFolder(targetFolder);

                    // Update pattern order for regular folders
                    Map<String, List<String>> orderMap = loadPatternOrder();
                    List<String> order = new ArrayList<>(orderMap.getOrDefault(targetFolder, new ArrayList<>()));

                    order.remove(uniqueName);
                    if (insertionIndex != null && insertionIndex >= 0) {
                        int insertAt = Math.min(insertionIndex, order.size());
                        order.add(insertAt, uniqueName);
                    } else {
                        order.add(uniqueName);
                    }

                    orderMap.put(targetFolder, order);
                    savePatternOrder(orderMap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String generateNextPatternName(String folder) {
        List<SavedPattern> existing = getSavedPatterns(folder);
        Set<String> names = existing.stream()
                .map(SavedPattern::getName)
                .collect(Collectors.toSet());

        int counter = 1;
        String baseName = "Pattern";
        while (names.contains(baseName + counter)) {
            counter++;
        }

        return baseName + counter;
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // Integration points
    public static void loadPattern(SavedPattern pattern) {
        if (ValidationConstants.DEBUG) {System.out.println("--- PatternStorage.loadPattern: Preparing to load patterns ---");}

        // Set the patterns to be loaded when GUI opens
        boolean fromCasted = CASTED_FOLDER.equals(pattern.getFolder());
        PatternCache.setPendingPatterns(pattern.getPatterns(), fromCasted);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Try method 1: Direct GUI opening with a small delay
        mc.execute(() -> {
            // Schedule with a small delay to ensure current screen closes first
            java.util.concurrent.CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
                mc.execute(() -> {
                    if (ValidationConstants.DEBUG) {System.out.println("Attempting to open staff GUI...");}

                    // Method 1: Try with MAIN_HAND first
                    try {
                        mc.setScreen(new GuiSpellcasting(InteractionHand.MAIN_HAND, new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 0));
                        if (ValidationConstants.DEBUG) {System.out.println("Opened staff GUI with MAIN_HAND");}
                    } catch (Exception e1) {
                        // Method 2: Try OFF_HAND
                        try {
                            mc.setScreen(new GuiSpellcasting(InteractionHand.OFF_HAND, new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 0));
                            if (ValidationConstants.DEBUG) {System.out.println("Opened staff GUI with OFF_HAND");}
                        } catch (Exception e2) {
                            if (ValidationConstants.DEBUG_ERROR) {System.out.println("Failed to open staff GUI with mainhand/offhand: " + e2.getMessage());}
                        }
                    }
                });
            });
        });
    }

    public static void onPatternsCast(List<HexPattern> patterns) {
        if (patterns.isEmpty()) {
            return;
        }

        long timestamp = System.currentTimeMillis();
        String fileName = "Casted_" + timestamp;

        savePattern(fileName, patterns, CASTED_FOLDER);

        // Add pattern order management
        Map<String, List<String>> orderMap = loadPatternOrder();
        List<String> castedOrder = new ArrayList<>(orderMap.getOrDefault(CASTED_FOLDER, new ArrayList<>()));

        // Count favorites
        int favoriteCount = 0;
        for (String name : castedOrder) {
            String key = CASTED_FOLDER + "/" + name;
            if (favoritesCache.getOrDefault(key, false)) {
                favoriteCount++;
            }
        }

        // Insert at top (after favorites)
        castedOrder.add(favoriteCount, fileName);
        orderMap.put(CASTED_FOLDER, castedOrder);
        savePatternOrder(orderMap);
    }
}