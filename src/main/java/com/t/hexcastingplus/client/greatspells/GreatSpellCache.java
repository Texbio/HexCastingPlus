package com.t.hexcastingplus.client.greatspells;

import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified cache manager for great spell patterns
 * Ensures both BruteforceManager and ScrollScanner use the same format
 */
public class GreatSpellCache {
    private static Map<String, String> cache = new HashMap<>();
    private static boolean loaded = false;
    private static String cacheFilePath = null;

    /**
     * Get a pattern from cache
     */
    public static String getPattern(String opId) {
        ensureLoaded();
        return cache.get(opId);
    }

    /**
     * Save a pattern to cache
     */
    public static boolean savePattern(String opId, String angleSignature) {
        ensureLoaded();

        // Check if already exists with same signature
        String existing = cache.get(opId);
        if (existing != null && existing.equals(angleSignature)) {
            return true; // Already saved
        }

        try {
            Path cacheFile = getCacheFile();

            // Create file with header if doesn't exist
            if (!Files.exists(cacheFile)) {
                createCacheFile(cacheFile);
            }

            // Append the new pattern
            String csvLine = opId + "," + angleSignature + "\n";
            Files.write(cacheFile, csvLine.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            // Update in-memory cache
            cache.put(opId, angleSignature);

            if (ValidationConstants.DEBUG) {System.out.println("[GreatSpellCache] Saved: " + opId + " -> " + angleSignature);}

            return true;
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.err.println("[GreatSpellCache] Failed to save: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Force reload the cache
     */
    public static void reload() {
        loaded = false;
        cache.clear();
        ensureLoaded();
    }

    /**
     * Get all cached patterns
     */
    public static Map<String, String> getAllPatterns() {
        ensureLoaded();
        return new HashMap<>(cache);
    }

    private static void ensureLoaded() {
        if (loaded) return;

        try {
            Path cacheFile = getCacheFile();
            cacheFilePath = cacheFile.toString();

            if (Files.exists(cacheFile)) {
                List<String> lines = Files.readAllLines(cacheFile);

                for (String line : lines) {
                    // Skip comments
                    if (line.trim().startsWith("//") || line.trim().isEmpty()) {
                        continue;
                    }

                    // Parse CSV format: opId,angleSignature,direction
                    String[] parts = line.split(",", 2);
                    if (parts.length == 2) {
                        String opId = parts[0].trim();
                        String signature = parts[1].trim();
                        cache.put(opId, signature);
                    }
                }

                if (ValidationConstants.DEBUG) {
                    System.out.println("[GreatSpellCache] Loaded " + cache.size() + " patterns from " + cacheFile);
                }
            } else {
                if (ValidationConstants.DEBUG) {
                    System.out.println("[GreatSpellCache] No cache file found at " + cacheFile);
                }
            }

            loaded = true;
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.err.println("[GreatSpellCache] Failed to load: " + e.getMessage());
                e.printStackTrace();
            }
            loaded = true; // Prevent infinite retry
        }
    }

    private static Path getCacheFile() {
        Path cacheDir = HexCastingPlusClientConfig.getModDirectory()
                .resolve(HexCastingPlusClientConfig.GREAT_SPELLS_FOLDER);

        try {
            Files.createDirectories(cacheDir);
        } catch (Exception e) {
            // Ignore
        }

        return cacheDir.resolve(getCacheFileName());
    }

    private static String getCacheFileName() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.hasSingleplayerServer()) {
            try {
                var serverWorld = mc.getSingleplayerServer().overworld();
                long worldSeed = serverWorld.getSeed();
                return "sp_" + Long.toUnsignedString(worldSeed) + ".csv";
            } catch (Exception e) {
                return "sp_unknown.csv";
            }
        } else {
            var connection = mc.getConnection();
            if (connection != null && connection.getConnection() != null) {
                String serverAddress = connection.getConnection().getRemoteAddress().toString();
                serverAddress = serverAddress.replace("/", "").replace(":", "_");
                return "mp_" + sanitizeFileName(serverAddress) + ".csv";
            }
            return "mp_unknown.csv";
        }
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static void createCacheFile(Path cacheFile) throws Exception {
        String worldName = "Unknown";

        try {
            if (Minecraft.getInstance().hasSingleplayerServer()) {
                var serverWorld = Minecraft.getInstance().getSingleplayerServer().overworld();
                worldName = serverWorld.getSeed() + " - " +
                        Minecraft.getInstance().getSingleplayerServer().getWorldData().getLevelName();
            } else {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null && connection.getConnection() != null) {
                    worldName = connection.getConnection().getRemoteAddress().toString();
                }
            }
        } catch (Exception e) {
            // Keep "Unknown"
        }

        String header = "// " + worldName + "\n";
        Files.write(cacheFile, header.getBytes());
    }
}