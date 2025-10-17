package com.t.hexcastingplus.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.client.Minecraft;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class HexCastingPlusClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LOG_PREFIX = "[HexCastingPlus] ";

    // Centralized folder and file constants
    public static final String MOD_FOLDER = "hexcastingplus";
    public static final String PATTERNS_FOLDER = "hexcasting_patterns";
    public static final String GREAT_SPELLS_FOLDER = "resolved_great_spells";

    // Config files (stored in root mod directory)
    public static final String CLIENT_CONFIG_FILE = "hexcastingplus-client.json";

    // Pattern-related files (stored in patterns directory)
    public static final String PATTERNS_CONFIG_FILE = "hexcastingplus_config.json";
    public static final String FAVORITES_FILE = "favorites.json";
    public static final String PATTERN_ORDER_FILE = "pattern_order.json";
    public static final String FOLDER_ORDER_FILE = "folder_order.json";
    public static final String CATEGORY_STATE_FILE = "category_state.json";
    public static final String NUMBER_CACHE_FILE = "number-cache.csv";

    // Special folders within patterns directory
    public static final String TRASH_FOLDER = ".trash";
    public static final String CASTED_FOLDER = ".casted";
    public static final String RECYCLE_BIN_FOLDER = ".recyclebin";

    private static ConfigData config = new ConfigData();

    public static class ConfigData {
        public boolean useDrawingMethod = false; // Default to packet method
        // Add more config options here later
    }

    /**
     * Gets the base directory for the mod
     * On Windows: %APPDATA%/hexcastingplus/
     * On Linux/Mac: .minecraft/hexcastingplus/
     */
    public static Path getModDirectory() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows: Use %APPDATA%
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return Path.of(appData, MOD_FOLDER);
            }
        }

        // Linux, Mac, or fallback: Use .minecraft folder
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MOD_FOLDER);
    }

    /**
     * Gets the patterns directory
     * Returns: modDirectory/hexcasting_patterns/
     */
    public static Path getPatternsDirectory() {
        return getModDirectory().resolve(PATTERNS_FOLDER);
    }

    public static void load() {
        try {
            Path configPath = getModDirectory().resolve(CLIENT_CONFIG_FILE);

            // Ensure mod directory exists
            Files.createDirectories(getModDirectory());

            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                config = GSON.fromJson(json, ConfigData.class);
                if (config == null) config = new ConfigData();
            }
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println(LOG_PREFIX + "Error loading config: " + e.getMessage());
            }
            config = new ConfigData();
        }
    }

    public static void save() {
        try {
            Path configPath = getModDirectory().resolve(CLIENT_CONFIG_FILE);

            // Ensure mod directory exists
            Files.createDirectories(getModDirectory());

            Files.writeString(configPath, GSON.toJson(config));
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println(LOG_PREFIX + "Error saving config: " + e.getMessage());
            }
        }
    }

    public static boolean useDrawingMethod() {
        return config.useDrawingMethod;
    }

    public static void setUseDrawingMethod(boolean value) {
        config.useDrawingMethod = value;
        save();
    }

    /**
     * Moves a folder to the recycle bin
     * @param folderPath The path of the folder to move
     * @return true if successful, false otherwise
     */
    public static boolean moveToRecycleBin(Path folderPath) {
        if (ValidationConstants.DEBUG) {
            System.out.println(LOG_PREFIX + "Attempting to delete folder: " + folderPath.getFileName());
        }

        if (!Files.exists(folderPath)) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println(LOG_PREFIX + "Folder does not exist: " + folderPath);
            }
            return false;
        }

        try {
            // Try Desktop.moveToTrash first
            if (Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {

                if (ValidationConstants.DEBUG) {
                    System.out.println(LOG_PREFIX + "Using system recycle bin...");
                }
                boolean result = Desktop.getDesktop().moveToTrash(folderPath.toFile());

                if (result) {
                    if (ValidationConstants.DEBUG) {
                        System.out.println(LOG_PREFIX + "Successfully moved to system recycle bin");
                    }
                    return true;
                } else {
                    if (ValidationConstants.DEBUG) {
                        System.out.println(LOG_PREFIX + "System recycle bin operation returned false");
                    }
                }
            } else {
                if (ValidationConstants.DEBUG) {
                    System.out.println(LOG_PREFIX + "System recycle bin not supported (Java version: " +
                            System.getProperty("java.version") + ", OS: " + System.getProperty("os.name") + ")");
                }
            }

            // Fallback: Move to local .recyclebin folder
            if (ValidationConstants.DEBUG) {
                System.out.println(LOG_PREFIX + "Using local .recyclebin folder as fallback");
            }
            Path recycleBinPath = getPatternsDirectory().resolve(RECYCLE_BIN_FOLDER);
            Files.createDirectories(recycleBinPath);

            // Generate unique destination name with timestamp
            String folderName = folderPath.getFileName().toString();
            String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            );
            Path destination = recycleBinPath.resolve(folderName + "_" + timestamp);

            // Handle duplicates
            int counter = 1;
            while (Files.exists(destination)) {
                destination = recycleBinPath.resolve(folderName + "_" + timestamp + "_" + counter);
                counter++;
            }

            // Move the folder
            Files.move(folderPath, destination);
            if (ValidationConstants.DEBUG) {
                System.out.println(LOG_PREFIX + "Moved to local recycle bin: " + destination.getFileName());
            }
            return true;

        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println(LOG_PREFIX + "Failed to move folder to recycle bin: " + e.getMessage());
            }
            return false;
        }
    }
}