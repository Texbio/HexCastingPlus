package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.eval.ResolvedPattern;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.common.lib.HexSounds;
import at.petrak.hexcasting.common.msgs.MsgNewSpellPatternC2S;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import com.t.hexcastingplus.common.pattern.PatternCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.InteractionHand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class PatternDrawingHelper {

    public static void addPatternViaPacket(GuiSpellcasting staffGui, HexPattern pattern, String patternName) {
        try {
            if (ValidationConstants.DEBUG) {
                System.out.println("[DEBUG PACKET] Adding pattern: " + patternName);
                System.out.println("  - Pattern: " + pattern.anglesSignature() + "," + pattern.getStartDir());
            }

            try {
                Method setExpecting = staffGui.getClass().getMethod("hexcastingplus$setExpectingResponse", boolean.class);
                setExpecting.invoke(staffGui, true);
            } catch (Exception e) {
                if (ValidationConstants.DEBUG) {
                    System.out.println("[DEBUG] Failed to set expecting flag: " + e.getMessage());
                }
            }

            trackPattern(staffGui, pattern);
            PatternCache.addPattern(pattern);

            try {
                Field loadedCountField = staffGui.getClass().getDeclaredField("hexcastingplus$loadedPatternCount");
                loadedCountField.setAccessible(true);
                int currentCount = loadedCountField.getInt(staffGui);
                loadedCountField.setInt(staffGui, currentCount + 1);

                if (ValidationConstants.DEBUG) {
                    System.out.println("[DEBUG] Incremented loaded pattern count to: " + (currentCount + 1));
                }
            } catch (Exception e) {
                if (ValidationConstants.DEBUG) {
                    System.out.println("[DEBUG] Failed to increment loaded pattern count: " + e.getMessage());
                }
            }

            List<ResolvedPattern> currentPatterns = getPatterns(staffGui);
            InteractionHand hand = getHandUsed(staffGui);

            IClientXplatAbstractions.INSTANCE.sendPacketToServer(
                    new MsgNewSpellPatternC2S(hand, pattern, currentPatterns)
            );

            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(HexSounds.ADD_TO_PATTERN, 1.0f)
            );

            if (ValidationConstants.DEBUG) {
                System.out.println("Sent pattern via packet: " + patternName);
            }

        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("Error sending pattern packet: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    public static void addPatternViaDrawing(GuiSpellcasting staffGui, HexPattern pattern, String patternName) {
        try {
            HexCoord startCoord = findFreeStartPosition(staffGui, pattern);

            trackPattern(staffGui, pattern);
            PatternCache.addPattern(pattern);

            List<ResolvedPattern> currentPatterns = getPatterns(staffGui);
            currentPatterns.add(new ResolvedPattern(pattern, startCoord, ResolvedPatternType.UNRESOLVED));

            Set<HexCoord> patternPositions = new HashSet<>(pattern.positions(startCoord));
            addUsedSpots(staffGui, patternPositions);

            InteractionHand hand = getHandUsed(staffGui);
            IClientXplatAbstractions.INSTANCE.sendPacketToServer(
                    new MsgNewSpellPatternC2S(hand, pattern, currentPatterns)
            );

            updatePatternCount(staffGui, currentPatterns.size());

            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(HexSounds.ADD_TO_PATTERN, 1.0f)
            );

            if (ValidationConstants.DEBUG) {
                System.out.println("Added pattern with visual drawing: " + patternName);
            }

        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("Error adding pattern via drawing: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    private static HexCoord findFreeStartPosition(GuiSpellcasting staffGui, HexPattern pattern) {
        Set<HexCoord> usedSpots = getUsedSpots(staffGui);
        HexCoord origin = new HexCoord(0, 0);

        if (canPatternFitAt(pattern, origin, usedSpots)) {
            return origin;
        }

        for (int radius = 1; radius <= 20; radius++) {
            Iterator<HexCoord> iter = origin.rangeAround(radius);
            while (iter.hasNext()) {
                HexCoord candidate = iter.next();
                if (canPatternFitAt(pattern, candidate, usedSpots)) {
                    if (ValidationConstants.DEBUG) {
                        System.out.println("Found free position at: q=" + getQ(candidate) + ", r=" + getR(candidate) + " (radius " + radius + ")");
                    }
                    return candidate;
                }
            }
        }

        HexCoord bestPosition = origin;
        int minOverlaps = Integer.MAX_VALUE;

        for (int radius = 0; radius <= 10; radius++) {
            Iterator<HexCoord> iter = origin.rangeAround(radius);
            while (iter.hasNext()) {
                HexCoord candidate = iter.next();
                int overlaps = countOverlaps(pattern, candidate, usedSpots);
                if (overlaps < minOverlaps) {
                    minOverlaps = overlaps;
                    bestPosition = candidate;
                    if (overlaps == 0) break;
                }
            }
            if (minOverlaps == 0) break;
        }

        if (ValidationConstants.DEBUG && minOverlaps > 0) {
            System.out.println("Warning: Could not find completely free space. Placing with " + minOverlaps + " overlaps.");
        }

        return bestPosition;
    }

    private static int countOverlaps(HexPattern pattern, HexCoord startPos, Set<HexCoord> usedSpots) {
        int overlaps = 0;
        List<HexCoord> positions = pattern.positions(startPos);
        for (HexCoord pos : positions) {
            if (usedSpots.contains(pos)) {
                overlaps++;
            }
        }
        return overlaps;
    }

    private static Field qField;
    private static Field rField;

    static {
        try {
            qField = HexCoord.class.getDeclaredField("q");
            qField.setAccessible(true);
            rField = HexCoord.class.getDeclaredField("r");
            rField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int getQ(HexCoord coord) {
        try {
            return qField.getInt(coord);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int getR(HexCoord coord) {
        try {
            return rField.getInt(coord);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean canPatternFitAt(HexPattern pattern, HexCoord startPos, Set<HexCoord> usedSpots) {
        List<HexCoord> positions = pattern.positions(startPos);
        for (HexCoord pos : positions) {
            if (usedSpots.contains(pos)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Set<HexCoord> getUsedSpots(GuiSpellcasting staffGui) {
        try {
            Field usedSpotsField = GuiSpellcasting.class.getDeclaredField("usedSpots");
            usedSpotsField.setAccessible(true);
            return (Set<HexCoord>) usedSpotsField.get(staffGui);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static void addUsedSpots(GuiSpellcasting staffGui, Set<HexCoord> spots) {
        try {
            Field usedSpotsField = GuiSpellcasting.class.getDeclaredField("usedSpots");
            usedSpotsField.setAccessible(true);
            Set<HexCoord> usedSpots = (Set<HexCoord>) usedSpotsField.get(staffGui);
            usedSpots.addAll(spots);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ResolvedPattern> getPatterns(GuiSpellcasting staffGui) {
        try {
            Method method = staffGui.getClass().getMethod("hexcastingplus$getPatterns");
            return (List<ResolvedPattern>) method.invoke(staffGui);
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private static InteractionHand getHandUsed(GuiSpellcasting staffGui) {
        try {
            Method method = staffGui.getClass().getMethod("hexcastingplus$getHandUsed");
            return (InteractionHand) method.invoke(staffGui);
        } catch (Exception e) {
            return InteractionHand.MAIN_HAND;
        }
    }

    private static void trackPattern(GuiSpellcasting staffGui, HexPattern pattern) {
        try {
            Method method = staffGui.getClass().getMethod("hexcastingplus$trackPattern", HexPattern.class);
            method.invoke(staffGui, pattern);

            if (ValidationConstants.DEBUG) {
                System.out.println("[DEBUG TRACK] Pattern tracked successfully");
            }
        } catch (Exception e) {
            if (ValidationConstants.DEBUG) {
                System.out.println("[DEBUG TRACK] Failed to track pattern: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    private static void updatePatternCount(GuiSpellcasting staffGui, int count) {
        try {
            Field field = staffGui.getClass().getDeclaredField("hexcastingplus$lastPatternCount");
            field.setAccessible(true);
            field.set(staffGui, count);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}