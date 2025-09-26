package com.t.hexcastingplus.common.pattern;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.resources.ResourceLocation;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.ArrayList;

public class NumberPatternDecoder {

    public static Double decodeNumberSequence(List<HexPattern> patterns) {
        if (patterns.isEmpty()) return null;

        // Try to decode the first pattern as a number
        Double firstNumber = decodeSingleNumberPattern(patterns.get(0));
        if (firstNumber == null) return null;

        if (patterns.size() == 1) return firstNumber;

        // Look for operation sequences
        if (patterns.size() == 5) {
            Double second = decodeSingleNumberPattern(patterns.get(1));
            Double third = decodeSingleNumberPattern(patterns.get(2));

            if (second != null && third != null) {
                String op1 = getOperatorType(patterns.get(3));
                String op2 = getOperatorType(patterns.get(4));

                if (op1 != null && op2 != null) {
                    if (op1.equals("divide")) {
                        double fraction = second / third;
                        if (op2.equals("add")) {
                            return firstNumber + fraction;
                        } else if (op2.equals("subtract")) {
                            return firstNumber - fraction;
                        }
                    } else if (op1.equals("multiply")) {
                        double product = second * third;
                        if (op2.equals("add")) {
                            return firstNumber + product;
                        } else if (op2.equals("subtract")) {
                            return firstNumber - product;
                        }
                    }
                }
            }
        } else if (patterns.size() == 3) {
            // Check for simple operation: num1, num2, operator
            Double second = decodeSingleNumberPattern(patterns.get(1));
            if (second != null) {
                String op = getOperatorType(patterns.get(2));
                if (op != null) {
                    switch (op) {
                        case "add": return firstNumber + second;
                        case "subtract": return firstNumber - second;
                        case "multiply": return firstNumber * second;
                        case "divide": return second != 0 ? firstNumber / second : null;
                    }
                }
            }
        }

        return null;
    }

    private static String getOperatorType(HexPattern pattern) {
        try {
            var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
            String patternSig = pattern.anglesSignature();

            // Search through registry for matching pattern - match by signature only for operators
            for (var entry : registry.entrySet()) {
                ActionRegistryEntry actionEntry = entry.getValue();
                if (actionEntry == null || actionEntry.prototype() == null) continue;

                HexPattern registryPattern = actionEntry.prototype();

                // For operators, match by signature only (ignore direction)
                if (registryPattern.anglesSignature().equals(patternSig)) {
                    ResourceLocation resourceLoc = entry.getKey().location();
                    String path = resourceLoc.getPath();

                    // Check if this is a math operator
                    if (path.equals("add") || path.contains("add_distillation")) {
                        return "add";
                    }
                    if (path.equals("sub") || path.contains("sub_distillation")) {
                        return "subtract";
                    }
                    if (path.equals("mul") || path.contains("mul_distillation")) {
                        return "multiply";
                    }
                    if (path.equals("div") || path.contains("div_distillation")) {
                        return "divide";
                    }
                }
            }

        } catch (Exception e) {
            if (ValidationConstants.DEBUG_ERROR) {
                System.out.println("[NumberPatternDecoder] ERROR: Failed to access pattern registry!");
                System.out.println("  Exception: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return null;
    }

    public static Double decodeSingleNumberPattern(HexPattern pattern) {
        String sig = pattern.anglesSignature();

        if (sig.startsWith("aqaa") || sig.startsWith("dedd")) {
            boolean negative = sig.startsWith("dedd");
            String workingPart = sig.substring(4);

            double value = 0;
            for (char c : workingPart.toCharArray()) {
                switch (c) {
                    case 'w': value += 1; break;
                    case 'q': value += 5; break;
                    case 'e': value += 10; break;
                    case 'a': value *= 2; break;
                    case 'd': value /= 2; break;
                }
            }

            return negative ? -value : value;
        }

        return null;
    }
}