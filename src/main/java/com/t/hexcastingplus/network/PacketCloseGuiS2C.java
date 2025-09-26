package com.t.hexcastingplus.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketCloseGuiS2C {

    public static void encode(PacketCloseGuiS2C packet, FriendlyByteBuf buf) {
        // No data needed
    }

    public static PacketCloseGuiS2C decode(FriendlyByteBuf buf) {
        return new PacketCloseGuiS2C();
    }

    public static void handle(PacketCloseGuiS2C packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.setScreen(null);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}