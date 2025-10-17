package com.t.hexcastingplus.mixin;

import com.t.hexcastingplus.client.greatspells.ScrollScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for intercepting item use in MultiPlayerGameMode (client-side controller)
 */
@Mixin(MultiPlayerGameMode.class)
public class ScrollScanMixin {

    @Shadow
    private Minecraft minecraft;

    /**
     * Intercept right-click item use (works without shift)
     */
    @Inject(
            method = "useItem",
            at = @At("HEAD"),
            cancellable = true
    )
    //do not change this to localplayer, it uses player.
    private void hexcastingplus$onUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (player != null && player.level().isClientSide()) {
            ItemStack stack = player.getItemInHand(hand);

            // Try to scan the item
            InteractionResult result = ScrollScanner.scanItemStack(stack, hand);

            if (result == InteractionResult.SUCCESS) {
                // Cancel normal item use and return success
                cir.setReturnValue(InteractionResult.SUCCESS);
                cir.cancel();
            }
        }
    }

    /**
     * Intercept block right-clicks (also works without shift now)
     */
    @Inject(
            method = "useItemOn",
            at = @At("HEAD"),
            cancellable = true
    )
    private void hexcastingplus$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (player != null && player.level().isClientSide()) {
            ItemStack stack = player.getItemInHand(hand);

            // Try to scan the item - removed shift requirement
            InteractionResult result = ScrollScanner.scanItemStack(stack, hand);

            if (result == InteractionResult.SUCCESS) {
                // Cancel block interaction and return success
                cir.setReturnValue(InteractionResult.SUCCESS);
                cir.cancel();
            }
        }
    }
}