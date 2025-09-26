package com.t.hexcastingplus.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.t.hexcastingplus.client.bruteforce.BruteforceManager;

@Mixin(ChatComponent.class)
public class ChatMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void onAddMessage(Component message, CallbackInfo ci) {
        if (BruteforceManager.getInstance().isBruteforcing()) {
            String text = message.getString();
            if (text.contains("That pattern isn't associated with any action")) {
                ci.cancel();
                return;
            }
        }
    }
}