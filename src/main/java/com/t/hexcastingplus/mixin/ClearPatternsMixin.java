package com.t.hexcastingplus.mixin;

import at.petrak.hexcasting.common.msgs.MsgClearSpiralPatternsS2C;
import com.t.hexcastingplus.common.pattern.PatternTrackingHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MsgClearSpiralPatternsS2C.class, remap = false)
public class ClearPatternsMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private static void hexcastingplus$onClearPatternsMessage(CallbackInfo ci) {
//        System.out.println("=== CLEAR PATTERNS MESSAGE RECEIVED ===");

        // Mark that we need to reset tracking on next init
        PatternTrackingHelper.markForReset();

//        System.out.println("=== PATTERN CACHE CLEARED ===");
    }
}