package com.t.hexcastingplus.client;

import at.petrak.hexcasting.api.client.ClientCastingStack;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "hexcastingplus", value = Dist.CLIENT)
public class ClientEventHandler {

    @SubscribeEvent
    public static void onRenderWorldLast(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Get the client casting stack using the correct API
            ClientCastingStack stack = IClientXplatAbstractions.INSTANCE.getClientCastingStack(mc.player);

            // The stack handles its own rendering and updates
            // We don't need to do anything here unless we want custom rendering
        }
    }
}