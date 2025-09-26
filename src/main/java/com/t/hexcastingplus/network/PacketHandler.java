package com.t.hexcastingplus.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("hexcastingplus", "main"),
            () -> PROTOCOL_VERSION,
            s -> true,  // Accept any version from client (including missing)
            s -> true   // Accept any version from server (including missing)
    );

    public static void register() {
        int id = 0;

        INSTANCE.registerMessage(id++,
                PacketCloseGuiS2C.class,
                PacketCloseGuiS2C::encode,
                PacketCloseGuiS2C::decode,
                PacketCloseGuiS2C::handle
        );

        INSTANCE.registerMessage(id++,
                MsgOpenStaffGuiC2S.class,
                MsgOpenStaffGuiC2S::serialize,
                MsgOpenStaffGuiC2S::deserialize,
                MsgOpenStaffGuiC2S::handle
        );
    }
}