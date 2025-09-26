package com.t.hexcastingplus.mixin;

import at.petrak.hexcasting.api.casting.eval.ResolvedPattern;
import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.common.msgs.MsgNewSpellPatternC2S;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import com.t.hexcastingplus.client.NumberPatternGenerator;
import com.t.hexcastingplus.client.bruteforce.BruteforceManager;
import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import com.t.hexcastingplus.client.gui.*;
import com.t.hexcastingplus.common.pattern.PatternCache;
import com.t.hexcastingplus.common.pattern.PatternStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

@Mixin(GuiSpellcasting.class)
public abstract class StaffGuiMixin extends Screen implements PatternStorage.StaffGuiAccess {
    protected StaffGuiMixin(Component title) {
        super(title);
    }

    @Unique
    private Button hexcastingplus$openPatternManagerButton;

    @Unique
    private static List<HexPattern> hexcastingplus$allPatterns = new ArrayList<>();

    @Unique
    private static boolean hexcastingplus$clientInitiatedClear = false;

    @Unique
    private static List<HexPattern> hexcastingplus$preClearPatterns = new ArrayList<>();

    @Unique
    private int hexcastingplus$lastPatternCount = 0;

    @Unique
    private static boolean hexcastingplus$initialized = false;

    @Unique
    private static int hexcastingplus$drawnPatternCount = 0;

    @Unique
    private static int hexcastingplus$loadedPatternCount = 0;

    @Unique
    private Button hexcastingplus$hexPatternsButton;

    @Unique
    private Button hexcastingplus$bruteforceButton;

    @Unique
    private static boolean hexcastingplus$expectingServerResponse = false;

    @Unique
    private Button hexcastingplus$numberVectorButton;


    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        GuiSpellcasting self = (GuiSpellcasting) (Object) this;
        PatternCache.setActiveStaffGui(self);

        // Check if we need to reset due to an actual clear command
        if (com.t.hexcastingplus.common.pattern.PatternTrackingHelper.shouldReset()) {
            hexcastingplus$resetTracking();
            hexcastingplus$lastPatternCount = 0;
            hexcastingplus$initialized = false;
            com.t.hexcastingplus.common.pattern.PatternTrackingHelper.clearResetFlag();
            if (ValidationConstants.DEBUG) {System.out.println("Reset tracking due to clear command");}
        }

        // Capture the initial patterns from the staff
        List<ResolvedPattern> existingPatterns = hexcastingplus$getPatterns();
        List<CompoundTag> cachedStack = hexcastingplus$getCachedStack();
        hexcastingplus$lastPatternCount = existingPatterns.size();

        // Initialize tracking if not already done
        if (!hexcastingplus$initialized) {
            List<HexPattern> staffPatterns = new ArrayList<>();
            for (ResolvedPattern rp : existingPatterns) {
                HexPattern pattern = rp.getPattern();
                staffPatterns.add(pattern);
                hexcastingplus$trackPattern(pattern);
            }
            PatternCache.setMergedPatterns(staffPatterns);
            hexcastingplus$initialized = true;
            if (ValidationConstants.DEBUG) {System.out.println("Initialized tracking with " + existingPatterns.size() + " patterns");}
        }

        // Add at the beginning of init after setting active staff GUI:
        BruteforceManager bruteforceManager = BruteforceManager.getInstance();
        bruteforceManager.setStaffGui(self);

        // Add bruteforce button (B)
        hexcastingplus$bruteforceButton = Button.builder(
                        Component.literal("B"),
                        button -> {
                            if (!bruteforceManager.isBruteforceComplete()) {
                                bruteforceManager.startBruteforce();
                            }
                        })
                .pos(this.width - 75, 5)
                .size(20, 20)
                .tooltip(Tooltip.create(bruteforceManager.getButtonTooltip()))
                .build();

        // Add hex button (H)
        hexcastingplus$hexPatternsButton = Button.builder(
                        Component.literal("H"),
                        button -> {
                            hexcastingplus$setButtonsVisible(false); // Hide buttons before opening
                            Minecraft mc = Minecraft.getInstance();
                            mc.setScreen(new HexPatternSelectorScreen(self));
                        })
                .pos(this.width - 50, 5)
                .size(20, 20)
                .tooltip(Tooltip.create(Component.literal("Hex Patterns Library")))
                .build();

        // Add pattern manager button (P)
        hexcastingplus$openPatternManagerButton = Button.builder(
                        Component.literal("P"),
                        button -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.setScreen(new PatternManagerScreen(self));
                        })
                .pos(this.width - 25, 5)
                .size(20, 20)
                .tooltip(Tooltip.create(Component.literal("Pattern Manager")))
                .build();

        // Add number/vector button (N) - below P
        hexcastingplus$numberVectorButton = Button.builder(
                        Component.literal("N"),
                        button -> hexcastingplus$openNumberVectorInput())
                .pos(this.width - 25, 30)  // 30 = 5 (top margin) + 20 (button height) + 5 (spacing)
                .size(20, 20)
                .tooltip(Tooltip.create(Component.literal("Input Number/Vector")))
                .build();

        // Make button inactive if bruteforcing is complete
        if (bruteforceManager.isBruteforceComplete()) {
            hexcastingplus$bruteforceButton.active = false;
        }

        bruteforceManager.setBruteforceButton(hexcastingplus$bruteforceButton);

        this.addRenderableWidget(hexcastingplus$hexPatternsButton);
        this.addRenderableWidget(hexcastingplus$bruteforceButton);
        this.addRenderableWidget(hexcastingplus$openPatternManagerButton);
        this.addRenderableWidget(hexcastingplus$numberVectorButton);

        if (ValidationConstants.DEBUG) {
            System.out.println("[DEBUG] StaffGuiMixin.init:");
            System.out.println("  - hexcastingplus$initialized: " + hexcastingplus$initialized);
            System.out.println("  - hexcastingplus$allPatterns size: " + hexcastingplus$allPatterns.size());
            System.out.println("  - Existing patterns from staff: " + existingPatterns.size());
            System.out.println("  - Cached stack size: " + cachedStack.size());
        }
    }

    @Unique
    private void hexcastingplus$openNumberVectorInput() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new NumberVectorInputDialog(this, (GuiSpellcasting)(Object)this));
    }

    // Used by PatternDrawingHelper
    @Unique
    public void hexcastingplus$setExpectingResponse(boolean expecting) {
        hexcastingplus$expectingServerResponse = expecting;
    }

    // Used by HexPatternSelectorScreen
    @Unique
    public void hexcastingplus$setButtonsVisible(boolean visible) {
        if (hexcastingplus$openPatternManagerButton != null) {
            hexcastingplus$openPatternManagerButton.visible = visible;
        }
        if (hexcastingplus$hexPatternsButton != null) {
            hexcastingplus$hexPatternsButton.visible = visible;
        }
        if (hexcastingplus$numberVectorButton != null) {
            hexcastingplus$numberVectorButton.visible = visible;
        }
        if (hexcastingplus$bruteforceButton != null) {
            hexcastingplus$bruteforceButton.visible = visible;
        }
    }

    // used by HexPatternSelectorScreen
    @Unique
    public void hexcastingplus$bruteforceSpecificPattern(String patternName) {
        BruteforceManager.getInstance().bruteforceSpecificPattern(patternName);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickBruteforce(CallbackInfo ci) {
        BruteforceManager bruteforceManager = BruteforceManager.getInstance();
        bruteforceManager.tick();

        // Update button tooltip and active state
        if (hexcastingplus$bruteforceButton != null) {
            hexcastingplus$bruteforceButton.setTooltip(Tooltip.create(bruteforceManager.getButtonTooltip()));

            // Disable button if complete (prevents white highlight when clicking)
            if (!bruteforceManager.isBruteforcing() && bruteforceManager.isBruteforceComplete()) {
                hexcastingplus$bruteforceButton.active = false;
            } else if (!bruteforceManager.isBruteforcing()) {
                hexcastingplus$bruteforceButton.active = true;
            }
        }
    }

    // ==== hide show patterns
    @Unique
    private List<ResolvedPattern> hexcastingplus$tempPatterns = null;

    @Unique
    private static boolean hexcastingplus$hidePatternsForLibrary = false;

    // Used in alot of places
    @Unique
    public void hexcastingplus$setHidePatternsForLibrary(boolean hide) {
        hexcastingplus$hidePatternsForLibrary = hide;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void hidePatterns(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (hexcastingplus$hidePatternsForLibrary) {
            List<ResolvedPattern> patterns = hexcastingplus$getPatterns();
            hexcastingplus$tempPatterns = new ArrayList<>(patterns);
            patterns.clear();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void restorePatterns(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (hexcastingplus$hidePatternsForLibrary && hexcastingplus$tempPatterns != null) {
            List<ResolvedPattern> patterns = hexcastingplus$getPatterns();
            patterns.addAll(hexcastingplus$tempPatterns);
            hexcastingplus$tempPatterns = null;
        }
    }
    // ====

    @Override
    public void onClose() {
        if (BruteforceManager.getInstance().isBruteforcing()) {
            BruteforceManager.getInstance().stopBruteforce();
        }
        super.onClose();
    }

    // Used by HexPatternSelectorScreen
    @Unique
    public void hexcastingplus$addHexPattern(String angleSignature, HexDir startDir, String patternName) {
        GuiSpellcasting self = (GuiSpellcasting) (Object) this;
        HexPattern pattern = HexPattern.fromAngles(angleSignature, startDir);

        if (HexCastingPlusClientConfig.useDrawingMethod()) {
            PatternDrawingHelper.addPatternViaDrawing(self, pattern, patternName);
        } else {
            PatternDrawingHelper.addPatternViaPacket(self, pattern, patternName);
        }
    }

    @Unique
    private static void hexcastingplus$resetTracking() {
        hexcastingplus$allPatterns.clear();
        hexcastingplus$initialized = false;
        hexcastingplus$drawnPatternCount = 0;
        hexcastingplus$loadedPatternCount = 0;
        if (ValidationConstants.DEBUG) {System.out.println("Reset pattern tracking");}
    }

    // ==================
    // check pattern

    @Inject(method = "recvServerUpdate", at = @At("HEAD"), remap = false)
    public void onRecvServerUpdate(ExecutionClientView info, int index, CallbackInfo ci) {
        // Clear the expecting flag when we get any server response
        hexcastingplus$expectingServerResponse = false;

        if (BruteforceManager.getInstance().isBruteforcing()) {
            BruteforceManager.getInstance().handleServerResponse(info.getResolutionType());
        }

        // Check GUI patterns instead of stack clear
        List<ResolvedPattern> currentGuiPatterns = hexcastingplus$getPatterns();
        boolean guiPatternsEmpty = currentGuiPatterns.isEmpty();

//        if (ValidationConstants.DEBUG) {
//            System.out.println("[DEBUG] Server update received:");
//            System.out.println("  - info.isStackClear: " + info.isStackClear());
//            System.out.println("  - guiPatternsEmpty: " + guiPatternsEmpty);
//            System.out.println("  - GUI patterns count: " + currentGuiPatterns.size());
//            System.out.println("  - tracked patterns: " + hexcastingplus$allPatterns.size());
//            System.out.println("  - hexcastingplus$clientInitiatedClear: " + hexcastingplus$clientInitiatedClear);
//            System.out.println("  - hexcastingplus$preClearPatterns size: " + hexcastingplus$preClearPatterns.size());
//        }

        // If stack is clear AND GUI is about to close (will happen in GuiSpellcasting.recvServerUpdate)
        // Save patterns before the GUI closes
        if (info.isStackClear() && !hexcastingplus$clientInitiatedClear && !hexcastingplus$allPatterns.isEmpty()) {
            // The GUI will close after this, so save patterns now
            hexcastingplus$preClearPatterns = new ArrayList<>(hexcastingplus$allPatterns);
            if (ValidationConstants.DEBUG) {
                System.out.println("Stack clear detected - saving " + hexcastingplus$preClearPatterns.size() + " patterns before GUI closes");
                System.out.println("  Loaded count: " + hexcastingplus$loadedPatternCount);
                System.out.println("  Drawn count: " + hexcastingplus$drawnPatternCount);
            }

            if (hexcastingplus$loadedPatternCount >= 2 || hexcastingplus$drawnPatternCount >= 1) {
                PatternStorage.onPatternsCast(hexcastingplus$preClearPatterns);
                if (ValidationConstants.DEBUG) {
                    System.out.println("Patterns saved to .casted folder");
                }
            } else {
                System.out.println("NOT SAVING - counts too low: loaded=" + hexcastingplus$loadedPatternCount + ", drawn=" + hexcastingplus$drawnPatternCount);
            }

            // Reset tracking since we're about to close
            hexcastingplus$resetTracking();
        }
    }

    // ==================


    @Unique
    public void hexcastingplus$trackPattern(HexPattern pattern) {
        hexcastingplus$allPatterns.add(pattern);
        if (ValidationConstants.DEBUG) {
            System.out.println("[DEBUG TRACK] hexcastingplus$trackPattern called:");
            System.out.println("  - Total tracked: " + hexcastingplus$allPatterns.size());
            System.out.println("  - Initialized: " + hexcastingplus$initialized);
        }
    }

    @Unique
    public InteractionHand hexcastingplus$getHandUsed() {
        try {
            Method getHandUsed = GuiSpellcasting.class.getMethod("getHandUsed");
            return (InteractionHand) getHandUsed.invoke(this);
        } catch (Exception e) {
            try {
                Field[] fields = GuiSpellcasting.class.getDeclaredFields();
                for (Field field : fields) {
                    field.setAccessible(true);
                    if (field.getType() == InteractionHand.class) {
                        return (InteractionHand) field.get(this);
                    }
                }
            } catch (Exception e2) {
                e2.printStackTrace();
            }
            return InteractionHand.MAIN_HAND;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    public List<ResolvedPattern> hexcastingplus$getPatterns() {
        try {
            Field patternsField = GuiSpellcasting.class.getDeclaredField("patterns");
            patternsField.setAccessible(true);
            Object value = patternsField.get(this);
            if (value instanceof List) {
                return (List<ResolvedPattern>) value;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return List.of();
    }

    @Unique
    @SuppressWarnings("unchecked")
    public List<CompoundTag> hexcastingplus$getCachedStack() {
        try {
            Field cachedStackField = GuiSpellcasting.class.getDeclaredField("cachedStack");
            cachedStackField.setAccessible(true);
            Object value = cachedStackField.get(this);

            if (value instanceof List) {
                List<CompoundTag> stack = (List<CompoundTag>) value;
                if (ValidationConstants.DEBUG) {System.out.println("Found cached stack with " + stack.size() + " items:");}

//                // too much spam
//                for (int i = 0; i < stack.size(); i++) {
//                    CompoundTag tag = stack.get(i);
//                    if (ValidationConstants.DEBUG) {
//                        System.out.println("  Item " + i + ": " + tag.toString());
//                        if (tag.contains("hexcasting:type")) {
//                            System.out.println("    Type: " + tag.getString("hexcasting:type"));
//                        }
//                    }
//                }

                return stack;
            } else {
                if (ValidationConstants.DEBUG_ERROR) {System.out.println("cachedStack is not a List, it's: " + (value != null ? value.getClass().getName() : "null"));}
            }
        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {System.out.println("No cached stack found");}
        }
        return List.of();
    }

    // Used by sendNumber()
    @Unique
    public void hexcastingplus$sendNumberPattern(double value) {
        NumberPatternGenerator.PatternSequence sequence = NumberPatternGenerator.convertToPatternSequence(value);

        if (sequence != null) {
            for (NumberPatternGenerator.PatternSequence.PatternComponent component : sequence.components) {
                HexPattern pattern = HexPattern.fromAngles(component.pattern, component.startDir);
                PatternDrawingHelper.addPatternViaPacket(
                        (GuiSpellcasting)(Object)this,
                        pattern,
                        component.name
                );
            }
        }
    }

    @Inject(method = "mouseReleased", at = @At("RETURN"))
    private void onPatternComplete(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        List<ResolvedPattern> currentPatterns = hexcastingplus$getPatterns();
        if (currentPatterns.size() > hexcastingplus$lastPatternCount) {
            ResolvedPattern newPattern = currentPatterns.get(currentPatterns.size() - 1);
            HexPattern pattern = newPattern.getPattern();

            if (ValidationConstants.DEBUG) {
                System.out.println("=== PATTERN DRAWN ===");
                System.out.println("Pattern angles signature: " + pattern.anglesSignature());
                System.out.println("Start direction: " + pattern.getStartDir());

                // See what NBT will be created
                CompoundTag nbt = pattern.serializeToNBT();
                System.out.println("NBT that will be saved:");
                System.out.println("  Full NBT: " + nbt.toString());
                if (nbt.contains("start_dir")) {
                    System.out.println("  start_dir byte: " + nbt.getByte("start_dir"));
                }
                if (nbt.contains("angles")) {
                    byte[] angles = nbt.getByteArray("angles");
                    System.out.print("  angles as bytes: [");
                    for (byte b : angles) {
                        System.out.print(b + " ");
                    }
                    System.out.println("]");

                    // Convert to the letter representation
                    System.out.print("  angles as letters: ");
                    for (byte b : angles) {
                        char c = hexcastingplus$ordinalToAngleChar(b);
                        System.out.print(c);
                    }
                    System.out.println();
                }
                System.out.println("====================");
            }

            hexcastingplus$drawnPatternCount++;
            hexcastingplus$trackPattern(pattern);
            PatternCache.addPattern(pattern);
            hexcastingplus$lastPatternCount = currentPatterns.size();
        }
    }

    @Unique
    private char hexcastingplus$ordinalToAngleChar(int ordinal) {
        switch (ordinal) {
            case 0: return 'w';
            case 1: return 'e';
            case 2: return 'd';
            case 3: return 's';
            case 4: return 'a';
            case 5: return 'q';
            default: return '?';
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        List<HexPattern> patternsToLoad = PatternCache.pollPendingPatterns();

        if (patternsToLoad != null) {
            if (ValidationConstants.DEBUG) {System.out.println("Staff GUI tick: Found " + patternsToLoad.size() + " pending patterns. Loading now.");}

            if (!patternsToLoad.isEmpty()) {
                hexcastingplus$loadedPatternCount++;
            }

            for (HexPattern pattern : patternsToLoad) {
                this.hexcastingplus$loadPattern(pattern);
            }
        }
    }

    @Unique
    public void hexcastingplus$loadPattern(HexPattern pattern) {
        List<ResolvedPattern> currentPatterns = hexcastingplus$getPatterns();
        int insertPosition = currentPatterns.size();

        if (ValidationConstants.DEBUG) {System.out.println("Loading pattern at position " + insertPosition + " (after " + insertPosition + " staff patterns)");}

        hexcastingplus$trackPattern(pattern);
        com.t.hexcastingplus.common.pattern.PatternCache.addPattern(pattern);
        hexcastingplus$lastPatternCount = currentPatterns.size();

        HexCoord origin = new HexCoord(0, 0);
        var msg = new MsgNewSpellPatternC2S(
                hexcastingplus$getHandUsed(),
                pattern,
                new ArrayList<>()
        );
        IClientXplatAbstractions.INSTANCE.sendPacketToServer(msg);
    }
}