// New file: CategoryStateManager.java
package com.t.hexcastingplus.client.gui;

import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class CategoryStateManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Boolean> categoryStates = new HashMap<>();

    public static void load() {
        try {
            Path statePath = HexCastingPlusClientConfig.getPatternsDirectory().resolve(HexCastingPlusClientConfig.CATEGORY_STATE_FILE);
            if (Files.exists(statePath)) {
                String json = Files.readString(statePath);
                Map<String, Boolean> loaded = GSON.fromJson(json, new TypeToken<Map<String, Boolean>>(){}.getType());
                if (loaded != null) {
                    categoryStates = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Path statePath = HexCastingPlusClientConfig.getPatternsDirectory().resolve(HexCastingPlusClientConfig.CATEGORY_STATE_FILE);
            Files.createDirectories(statePath.getParent());
            Files.writeString(statePath, GSON.toJson(categoryStates));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isCollapsed(String category) {
        return categoryStates.getOrDefault(category, false);
    }

    public static void toggleCategory(String category) {
        categoryStates.put(category, !isCollapsed(category));
        save();
    }
}