// New file: HexCastingPlusClientConfig.java
package com.t.hexcastingplus.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

public class HexCastingPlusClientConfig {
    private static final String CONFIG_FILE = "hexcastingplus-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ConfigData config = new ConfigData();

    public static class ConfigData {
        public boolean useDrawingMethod = false; // Default to packet method
        // Add more config options here later
    }

    public static void load() {
        try {
            Path configPath = Minecraft.getInstance().gameDirectory.toPath().resolve(CONFIG_FILE);
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                config = GSON.fromJson(json, ConfigData.class);
                if (config == null) config = new ConfigData();
            }
        } catch (Exception e) {
            e.printStackTrace();
            config = new ConfigData();
        }
    }

    public static void save() {
        try {
            Path configPath = Minecraft.getInstance().gameDirectory.toPath().resolve(CONFIG_FILE);
            Files.writeString(configPath, GSON.toJson(config));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean useDrawingMethod() {
        return config.useDrawingMethod;
    }

    public static void setUseDrawingMethod(boolean value) {
        config.useDrawingMethod = value;
        save();
    }
}