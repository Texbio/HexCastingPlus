package com.t.hexcastingplus;

import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import com.t.hexcastingplus.network.PacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Mod("hexcastingplus")
public class HexCastingPlus {
    public static final String MODID = "hexcastingplus";
    private static final Logger LOGGER = LogManager.getLogger();

    public HexCastingPlus() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::setup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        PacketHandler.register();
        LOGGER.info("HexCastingPlus initialized!");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Initialize config on client side
        event.enqueueWork(() -> {
            // Load client config
            HexCastingPlusClientConfig.load();

            // Perform migration if needed
            migrateFromOldLocation();

            LOGGER.info("HexCastingPlus client setup complete!");
        });
    }

    /**
     * Migrates existing files from old .minecraft location to new centralized location
     */
    private void migrateFromOldLocation() {
        try {
            Path oldPatternsLocation = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("hexcasting_patterns");
            Path newPatternsLocation = HexCastingPlusClientConfig.getPatternsDirectory();

            // Only migrate if old location exists and new doesn't
            if (Files.exists(oldPatternsLocation) && !Files.exists(newPatternsLocation)) {
                LOGGER.info("Migrating patterns from old location to new location...");

                // Create new directory structure
                Files.createDirectories(newPatternsLocation.getParent());

                // Move the entire patterns folder
                moveDirectory(oldPatternsLocation, newPatternsLocation);

                LOGGER.info("Successfully migrated patterns to: " + newPatternsLocation);
            }

            // Also migrate old bruteforce cache if it exists
            Path oldBruteforceLocation = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config")
                    .resolve("hexcastingplus")
                    .resolve("bruteforce");
            Path newGreatSpellsLocation = HexCastingPlusClientConfig.getModDirectory()
                    .resolve(HexCastingPlusClientConfig.GREAT_SPELLS_FOLDER);

            if (Files.exists(oldBruteforceLocation) && !Files.exists(newGreatSpellsLocation)) {
                LOGGER.info("Migrating bruteforce cache to resolved_great_spells...");
                Files.createDirectories(newGreatSpellsLocation.getParent());
                moveDirectory(oldBruteforceLocation, newGreatSpellsLocation);
                LOGGER.info("Successfully migrated great spells cache");
            }

            // Also check for the intermediate location (in case someone had the previous version)
            Path intermediateLocation = HexCastingPlusClientConfig.getModDirectory()
                    .resolve("bruteforce");
            if (Files.exists(intermediateLocation) && !Files.exists(newGreatSpellsLocation)) {
                LOGGER.info("Renaming bruteforce folder to resolved_great_spells...");
                Files.move(intermediateLocation, newGreatSpellsLocation, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Successfully renamed folder");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to migrate files from old location: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recursively moves a directory and all its contents
     */
    private void moveDirectory(Path source, Path target) throws Exception {
        if (!Files.exists(source)) return;

        Files.walk(source)
                .forEach(sourcePath -> {
                    try {
                        Path targetPath = target.resolve(source.relativize(sourcePath));
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.createDirectories(targetPath.getParent());
                            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to move file: " + sourcePath + " to " + target, e);
                    }
                });

        // Delete the old directory after successful move (only if empty)
        try {
            Files.walk(source)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            // Ignore - directory might not be empty
                        }
                    });
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}