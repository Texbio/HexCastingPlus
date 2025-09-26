package com.t.hexcastingplus;

import com.t.hexcastingplus.network.PacketHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
        LOGGER.info("HexCastingPlus client setup complete!");
    }
}