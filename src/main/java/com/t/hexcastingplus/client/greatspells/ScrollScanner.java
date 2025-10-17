package com.t.hexcastingplus.client.greatspells;

import at.petrak.hexcasting.api.casting.math.HexDir;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

public class ScrollScanner {
    // Spam prevention
    private static String lastMessageOpId = null;
    private static long lastMessageTime = 0;
    private static final long MESSAGE_COOLDOWN_MS = 3000; // 3 seconds before showing same message again

    /**
     * Scan an item stack to see if it contains a great spell scroll
     * @return true if successfully scanned and saved
     */
    public static InteractionResult scanItemStack(ItemStack stack, InteractionHand hand) {
        if (!isGreatSpellScroll(stack)) {
            return InteractionResult.PASS;
        }

        // Extract the pattern data
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("pattern") || !tag.contains("op_id")) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Scroll missing pattern or op_id data");
            }
            return InteractionResult.PASS;
        }

        String opId = tag.getString("op_id");

        // Check if we should show a message (cooldown for same spell)
        long currentTime = System.currentTimeMillis();
        boolean shouldShowMessage = true;

        if (opId.equals(lastMessageOpId) && (currentTime - lastMessageTime) < MESSAGE_COOLDOWN_MS) {
            shouldShowMessage = false; // Don't show message, still in cooldown
        }

        CompoundTag patternTag = tag.getCompound("pattern");

        // Extract pattern angles and direction
        if (!patternTag.contains("angles") || !patternTag.contains("start_dir")) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Scroll pattern missing angles or start_dir");
            }
            return InteractionResult.PASS;
        }

        byte[] angles = patternTag.getByteArray("angles");
        byte startDirOrdinal = patternTag.getByte("start_dir");

        // Convert to angle signature
        String angleSignature = convertToAngleSignature(angles);
        if (angleSignature == null || angleSignature.isEmpty()) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Failed to convert angles to signature");
            }
            return InteractionResult.PASS;
        }

        // Get the start direction
        HexDir startDir;
        try {
            startDir = HexDir.values()[startDirOrdinal];
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Invalid start_dir ordinal: " + startDirOrdinal);
            }
            return InteractionResult.PASS;
        }

        String fullSignature = angleSignature + "," + startDir.name();

        // Use the unified cache
        String existing = GreatSpellCache.getPattern(opId);

        // Check if already cached
        if (existing != null && existing.equals(fullSignature)) {
            // Already have this exact pattern
            if (shouldShowMessage) {
                sendChatMessage("Great spell already known: " + getSpellDisplayName(opId),
                        ChatFormatting.YELLOW);
                lastMessageOpId = opId;
                lastMessageTime = currentTime;
            }
            return InteractionResult.SUCCESS;
        }

        // Save to unified cache
        if (GreatSpellCache.savePattern(opId, fullSignature)) {
            if (shouldShowMessage) {
                sendChatMessage("Learned great spell: " + getSpellDisplayName(opId) + "!",
                        ChatFormatting.GREEN);
                lastMessageOpId = opId;
                lastMessageTime = currentTime;
            }

            // Trigger reload in BruteforceManager AND PatternResolver
            reloadCaches();

            return InteractionResult.SUCCESS;
        } else {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Failed to save pattern to cache");
            }
            return InteractionResult.FAIL;
        }
    }

    /**
     * Check if an item is a great spell scroll
     */
    private static boolean isGreatSpellScroll(ItemStack stack) {
        // Check if it's a scroll item using BuiltInRegistries for 1.20.1
        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!itemId.toString().equals("hexcasting:scroll")) {
            return false;
        }

        // Check if it has the required NBT data
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("op_id")) {
            return false;
        }

        String opId = tag.getString("op_id");

        // Check if this is a great spell (per-world pattern)
        return isGreatSpellOpId(opId);
    }

    /**
     * Check if an op_id represents a great spell
     */
    private static boolean isGreatSpellOpId(String opId) {
        // Check against the registry to see if this is a per-world pattern
        try {
            var registry = at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getActionRegistry();

            // Find the resource key for this op_id
            for (var key : registry.registryKeySet()) {
                if (key.location().toString().equals(opId)) {
                    // Check if it's a per-world pattern
                    try {
                        return registry.getHolderOrThrow(key)
                                .is(at.petrak.hexcasting.api.mod.HexTags.Actions.PER_WORLD_PATTERN);
                    } catch (Exception e) {
                        // Not a valid holder
                    }
                }
            }
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Failed to check registry: " + e.getMessage());
            }
        }

        // Fallback - assume hexcasting operations that aren't common are great spells
        return opId.startsWith("hexcasting:") && !opId.contains("common");
    }

    /**
     * Convert angle bytes to angle signature string
     */
    private static String convertToAngleSignature(byte[] angles) {
        StringBuilder signature = new StringBuilder();

        for (byte angle : angles) {
            char angleChar = ordinalToAngleChar(angle);
            if (angleChar == '?') {
                return null; // Invalid angle
            }
            signature.append(angleChar);
        }

        return signature.toString();
    }

    /**
     * Convert angle ordinal to character
     */
    private static char ordinalToAngleChar(int ordinal) {
        switch (ordinal) {
            case 0: return 'w'; // FORWARD
            case 1: return 'e'; // RIGHT
            case 2: return 'd'; // RIGHT_BACK
            case 3: return 's'; // BACK
            case 4: return 'a'; // LEFT_BACK
            case 5: return 'q'; // LEFT
            default: return '?';
        }
    }

    /**
     * Get a display name for a spell
     */
    private static String getSpellDisplayName(String opId) {
        // Extract the spell name from the operation ID
        if (opId.contains(":")) {
            String name = opId.substring(opId.lastIndexOf(':') + 1);
            // Convert snake_case to Title Case
            String[] parts = name.split("_");
            StringBuilder displayName = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    displayName.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) {
                        displayName.append(part.substring(1));
                    }
                    displayName.append(" ");
                }
            }
            return displayName.toString().trim();
        }
        return opId;
    }

    /**
     * Send a chat message to the player
     */
    private static void sendChatMessage(String message, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Component component = Component.literal(message).withStyle(color);
            mc.player.displayClientMessage(component, false);
        }
    }

    /**
     * Trigger a reload of all pattern caches
     */
    private static void reloadCaches() {
        // Force reload the unified cache
        GreatSpellCache.reload();

        // Reload BruteforceManager cache
        com.t.hexcastingplus.client.bruteforce.BruteforceManager.getInstance().reloadCache();

        // Force PatternResolver to reinitialize to pick up new patterns
        try {
            // Use reflection to clear the registry so it reloads
            java.lang.reflect.Field registryField = com.t.hexcastingplus.common.pattern.PatternResolver.class
                    .getDeclaredField("patternRegistry");
            registryField.setAccessible(true);
            registryField.set(null, null);

            java.lang.reflect.Field cacheField = com.t.hexcastingplus.common.pattern.PatternResolver.class
                    .getDeclaredField("angleSignatureCache");
            cacheField.setAccessible(true);
            cacheField.set(null, null);
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[ScrollScanner] Failed to clear PatternResolver cache: " + e.getMessage());
            }
        }

        // Now reinitialize
        com.t.hexcastingplus.common.pattern.PatternResolver.initializeRegistry();

        if (ValidationConstants.DEBUG) {
            System.out.println("[ScrollScanner] Reloaded all pattern caches");
        }
    }
}