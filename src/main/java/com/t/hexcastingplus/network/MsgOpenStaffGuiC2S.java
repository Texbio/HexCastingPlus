package com.t.hexcastingplus.network;

import at.petrak.hexcasting.common.msgs.MsgOpenSpellGuiS2C;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class MsgOpenStaffGuiC2S {
    private final InteractionHand hand;

    public MsgOpenStaffGuiC2S(InteractionHand hand) {
        this.hand = hand;
    }

    public static MsgOpenStaffGuiC2S deserialize(FriendlyByteBuf buf) {
        return new MsgOpenStaffGuiC2S(buf.readEnum(InteractionHand.class));
    }

    public static void serialize(MsgOpenStaffGuiC2S self, FriendlyByteBuf buf) {
        buf.writeEnum(self.hand);
    }

    public static void handle(MsgOpenStaffGuiC2S self, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }

            // Use the hand that was sent in the packet
            var hand = self.hand;
            var vm = IXplatAbstractions.INSTANCE.getStaffcastVM(sender, hand);
            var patterns = IXplatAbstractions.INSTANCE.getPatternsSavedInUi(sender);
            var descs = vm.generateDescs();

            IXplatAbstractions.INSTANCE.sendPacketToPlayer(sender,
                    new MsgOpenSpellGuiS2C(hand, patterns, descs.getFirst(), descs.getSecond(), 0));
        });
        ctx.get().setPacketHandled(true);
    }
}