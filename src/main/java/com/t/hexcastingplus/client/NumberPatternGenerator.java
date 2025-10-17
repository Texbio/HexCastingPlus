package com.t.hexcastingplus.client;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;

import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import at.petrak.hexcasting.api.casting.math.HexDir;
import com.t.hexcastingplus.client.gui.ValidationConstants;
import net.minecraft.client.Minecraft;

// CREDIT: https://github.com/Master-Bw3/Hex-Studio
// https://github.com/object-Object/hexnumgen-rs
public class NumberPatternGenerator {
    private static final String ADDITIVE_DISTILLATION = "waaw"; //00440
    private static final String DIVISION_DISTILLATION = "wdedw"; //002120
    private static final String MULTIPLICATIVE_DISTILLATION = "waqaw"; //204540
    private static final HexDir MULTIPLICATIVE_START = HexDir.SOUTH_WEST;
    private static final String SUBTRACTIVE_DISTILLATION = "wddw";
    private static final HexDir SUBTRACTIVE_START = HexDir.NORTH_WEST;

    // TreeMap with reverse order for highest to lowest sorting
    private static final Map<Double, String> patternCache = new TreeMap<>(Collections.reverseOrder());
    public static boolean cacheLoaded;

    // Cache of problem subsequences that cause collisions
    // Key format: "context:sequence" where context is the pattern before the problematic part
    private static final Map<String, Set<String>> failedParts = new HashMap<>();

    // Enable when done testing
    public static boolean USE_CACHE = true;

    enum Angle {
        FORWARD('w'),   // +1
        LEFT('q'),      // +5
        RIGHT('e'),     // +10
        LEFT_BACK('a'), // *2
        RIGHT_BACK('d'); // /2
        final char symbol;
        Angle(char symbol) { this.symbol = symbol; }
        double applyTo(double value) {
            switch(this) {
                case FORWARD: return value + 1;
                case LEFT: return value + 5;
                case RIGHT: return value + 10;
                case LEFT_BACK: return value * 2;
                case RIGHT_BACK: return value / 2;
                default: throw new IllegalStateException();
            }
        }
    }

    static class Path {
        final String pattern;
        final double value;
        final Set<Long> usedEdges;
        final Coord position;
        final Direction direction;
        final int consecutiveDoubles; // Track consecutive *2 operations
        final List<Integer> operations; // Track operations for debugging

        Path(String pattern, double value, Set<Long> usedEdges, Coord position,
             Direction direction, int consecutiveDoubles, List<Integer> operations) {
            this.pattern = pattern;
            this.value = value;
            this.usedEdges = usedEdges;
            this.position = position;
            this.direction = direction;
            this.consecutiveDoubles = consecutiveDoubles;
            this.operations = operations;
        }

        // Fast edge encoding method
        private static long encodeEdge(int x1, int y1, int x2, int y2) {
            // Normalize: always store with smaller coordinate first
            if (x1 > x2 || (x1 == x2 && y1 > y2)) {
                // Swap
                int tx = x1; x1 = x2; x2 = tx;
                int ty = y1; y1 = y2; y2 = ty;
            }
            // Pack into 64 bits: 16 bits per coordinate
            // Shift coordinates by 16384 to handle negatives (range: -16384 to +16383)
            long lx1 = (x1 + 16384) & 0xFFFF;
            long ly1 = (y1 + 16384) & 0xFFFF;
            long lx2 = (x2 + 16384) & 0xFFFF;
            long ly2 = (y2 + 16384) & 0xFFFF;

            return (lx1 << 48) | (ly1 << 32) | (lx2 << 16) | ly2;
        }

        static Path zero(boolean negative) {
            String prefix = negative ? "dedd" : "aqaa";
            Direction dir = Direction.NORTHEAST;  // Both use NORTHEAST now
            Set<Long> usedEdges = new HashSet<>();
            Coord pos = new Coord(0, 0);

            // Track all edges from the prefix pattern
            for (int i = 0; i < prefix.length(); i++) {
                char c = prefix.charAt(i);
                Direction nextDir = dir.turn(charToAngle(c));
                Coord nextPos = pos.move(nextDir);

                long edge = encodeEdge(pos.x, pos.y, nextPos.x, nextPos.y);
                usedEdges.add(edge);

                dir = nextDir;
                pos = nextPos;
            }

            return new Path(prefix, 0, usedEdges, pos, dir, 0, new ArrayList<>());
        }

        Path tryWithAngle(Angle angle, int operationValue) {
            // Calculate new value
            double newValue = angle.applyTo(this.value);

            // Check for consecutive doubles restriction (max 2 in a row)
            int newConsecutiveDoubles = (angle == Angle.LEFT_BACK) ? consecutiveDoubles + 1 : 0;
            if (newConsecutiveDoubles > 2) {
                return null; // Would create a triangle
            }

            // Calculate new direction and position
            Direction newDir = direction.turn(angle);
            Coord newPos = position.move(newDir);

            // Create edge encoding for the new line
            long newEdge = encodeEdge(position.x, position.y, newPos.x, newPos.y);

            // Check if edge already exists
            if (usedEdges.contains(newEdge)) {
                return null; // Edge already used - collision detected!
            }

            // ADDITIONAL CHECK: Verify the pattern is actually valid by rebuilding it
            // This catches edge cases where our tracking might be off
            if (pattern.length() > 8) { // Only check after initial setup
                if (!verifyPatternValid(pattern + angle.symbol)) {
                    return null;
                }
            }

            // Create new path with the edge added
            Set<Long> newEdges = new HashSet<>(usedEdges);
            newEdges.add(newEdge);

            List<Integer> newOps = new ArrayList<>(operations);
            newOps.add(operationValue);

            return new Path(
                    pattern + angle.symbol,
                    newValue,
                    newEdges,
                    newPos,
                    newDir,
                    newConsecutiveDoubles,
                    newOps
            );
        }

        // Add this verification method to Path class
        private static boolean verifyPatternValid(String patternToCheck) {
            Set<Long> edges = new HashSet<>();
            Coord pos = new Coord(0, 0);
            Direction dir = patternToCheck.startsWith("dedd") ? Direction.SOUTHEAST : Direction.NORTHEAST;

            for (char c : patternToCheck.toCharArray()) {
                Direction nextDir = dir.turn(charToAngle(c));
                Coord nextPos = pos.move(nextDir);

                long edge = encodeEdge(pos.x, pos.y, nextPos.x, nextPos.y);

                if (!edges.add(edge)) {
                    // Found duplicate edge - pattern loops back on itself
                    return false;
                }

                dir = nextDir;
                pos = nextPos;
            }

            return true;
        }

        // Find the exact operation sequence that causes a collision
        Path findCollisionPoint(List<Integer> testOperations) {
            Path current = Path.zero(value < 0);

            for (int i = 0; i < testOperations.size(); i++) {
                int op = testOperations.get(i);
                Path next = executeOperation(current, op);

                if (next == null) {
                    // Found the collision point!
                    // The problematic sequence is from the last successful point to here
                    return current;
                }
                current = next;
            }

            return current;
        }

        private static Path executeOperation(Path current, int op) {
            if (op == -2) {
                // Double operation
                return current.tryWithAngle(Angle.LEFT_BACK, op);
            } else if (op > 0) {
                // Add operation - break it down into 10s, 5s, 1s
                int remaining = op;
                Path result = current;

                while (remaining >= 10) {
                    result = result.tryWithAngle(Angle.RIGHT, 10);
                    if (result == null) return null;
                    remaining -= 10;
                }

                while (remaining >= 5) {
                    result = result.tryWithAngle(Angle.LEFT, 5);
                    if (result == null) return null;
                    remaining -= 5;
                }

                while (remaining > 0) {
                    result = result.tryWithAngle(Angle.FORWARD, 1);
                    if (result == null) return null;
                    remaining -= 1;
                }

                return result;
            }

            return current;
        }
    }

    static class Coord {
        final int x, y;
        Coord(int x, int y) {
            this.x = x;
            this.y = y;
        }
        Coord move(Direction dir) {
            switch(dir) {
                case NORTHEAST: return new Coord(x + 1, y - 1);
                case EAST: return new Coord(x + 2, y);
                case SOUTHEAST: return new Coord(x + 1, y + 1);
                case SOUTHWEST: return new Coord(x - 1, y + 1);
                case WEST: return new Coord(x - 2, y);
                case NORTHWEST: return new Coord(x - 1, y - 1);
                default: throw new IllegalStateException();
            }
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Coord)) return false;
            Coord c = (Coord) o;
            return x == c.x && y == c.y;
        }
        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }

    enum Direction {
        NORTHEAST, EAST, SOUTHEAST, SOUTHWEST, WEST, NORTHWEST;
        Direction turn(Angle angle) {
            switch(this) {
                case EAST:
                    switch(angle) {
                        case FORWARD: return EAST;
                        case LEFT_BACK: return NORTHWEST;
                        case LEFT: return NORTHEAST;
                        case RIGHT_BACK: return SOUTHWEST;
                        case RIGHT: return SOUTHEAST;
                    }
                case NORTHEAST:
                    switch(angle) {
                        case RIGHT: return EAST;
                        case LEFT: return NORTHWEST;
                        case LEFT_BACK: return WEST;
                        case FORWARD: return NORTHEAST;
                        case RIGHT_BACK: return SOUTHEAST;
                    }
                case NORTHWEST:
                    switch(angle) {
                        case RIGHT_BACK: return EAST;
                        case FORWARD: return NORTHWEST;
                        case LEFT: return WEST;
                        case RIGHT: return NORTHEAST;
                        case LEFT_BACK: return SOUTHWEST;
                    }
                case WEST:
                    switch(angle) {
                        case RIGHT_BACK: return NORTHEAST;
                        case RIGHT: return NORTHWEST;
                        case FORWARD: return WEST;
                        case LEFT_BACK: return SOUTHEAST;
                        case LEFT: return SOUTHWEST;
                    }
                case SOUTHWEST:
                    switch(angle) {
                        case LEFT_BACK: return EAST;
                        case RIGHT_BACK: return NORTHWEST;
                        case RIGHT: return WEST;
                        case LEFT: return SOUTHEAST;
                        case FORWARD: return SOUTHWEST;
                    }
                case SOUTHEAST:
                    switch(angle) {
                        case LEFT: return EAST;
                        case LEFT_BACK: return NORTHEAST;
                        case RIGHT_BACK: return WEST;
                        case FORWARD: return SOUTHEAST;
                        case RIGHT: return SOUTHWEST;
                    }
            }
            throw new IllegalStateException("Invalid direction/angle combination");
        }
    }

    public static class PatternSequence {
        public final List<PatternComponent> components;

        public PatternSequence() {
            this.components = new ArrayList<>();
        }

        public void addNumber(double value, String pattern) {
            components.add(new PatternComponent(pattern, HexDir.NORTH_EAST, "numerical_reflection_" + value));
        }

        public void addOperation(String pattern, HexDir startDir, String name) {
            components.add(new PatternComponent(pattern, startDir, name));
        }

        public static class PatternComponent {
            public final String pattern;
            public final HexDir startDir;
            public final String name;

            PatternComponent(String pattern, HexDir startDir, String name) {
                this.pattern = pattern;
                this.startDir = startDir;
                this.name = name;
            }
        }
    }

    // ============= new generation

    public static PatternSequence convertToPatternSequence(double target) {
        PatternSequence sequence = new PatternSequence();

        // Handle decimals first - split into integer and fractional parts
        if (target % 1 != 0) {
            BigDecimal bd = BigDecimal.valueOf(Math.abs(target));
            String targetStr = bd.toPlainString();
            String[] parts = targetStr.split("\\.");

            if (parts.length == 2) {
                long integerPart = Long.parseLong(parts[0]);
                String fracStr = parts[1].replaceAll("0+$", "");

                if (!fracStr.isEmpty()) {
                    int decimalPlaces = fracStr.length();
                    long divisor = (long)Math.pow(10, decimalPlaces);
                    long numerator = Long.parseLong(fracStr);

                    // Generate patterns for each component
                    List<PatternSequence.PatternComponent> intComponents = generateNumberComponents(
                            integerPart * (target < 0 ? -1 : 1)
                    );
                    List<PatternSequence.PatternComponent> numComponents = generateNumberComponents(numerator);
                    List<PatternSequence.PatternComponent> divComponents = generateNumberComponents(divisor);

                    if (intComponents != null && numComponents != null && divComponents != null) {
                        // Add integer part
                        for (PatternSequence.PatternComponent comp : intComponents) {
                            sequence.components.add(comp);
                        }

                        // Add fraction (numerator / divisor)
                        for (PatternSequence.PatternComponent comp : numComponents) {
                            sequence.components.add(comp);
                        }
                        for (PatternSequence.PatternComponent comp : divComponents) {
                            sequence.components.add(comp);
                        }
                        sequence.addOperation(DIVISION_DISTILLATION, HexDir.EAST, "division_distillation");

                        // Combine integer and fraction
                        addCombineOperation(sequence, target < 0);
                        return sequence;
                    }
                }
            }
        }

        // Handle integers (including large ones)
        List<PatternSequence.PatternComponent> components = generateNumberComponents(target);
        if (components != null) {
            for (PatternSequence.PatternComponent comp : components) {
                sequence.components.add(comp);
            }
            return sequence;
        }

        return null;
    }

    // New method that handles any number and returns a list of components
    private static List<PatternSequence.PatternComponent> generateNumberComponents(double value) {
        List<PatternSequence.PatternComponent> components = new ArrayList<>();
        boolean isNegative = value < 0;
        double absValue = Math.abs(value);

        // Handle large numbers (>999,999)
        if (absValue > 999999) {
            return generateLargeNumberComponents(value);
        }

        // Simple case - generate single pattern
        String pattern = convertToAngleSignature(value);
        if (pattern != null) {
            components.add(new PatternSequence.PatternComponent(
                    pattern,
                    HexDir.NORTH_EAST,
                    "numerical_reflection_" + value
            ));
            return components;
        }

        return null;
    }

    // Separate method for large number breakdown
    private static List<PatternSequence.PatternComponent> generateLargeNumberComponents(double value) {
        List<PatternSequence.PatternComponent> components = new ArrayList<>();
        boolean isNegative = value < 0;
        double absValue = Math.abs(value);

        long thousands = (long)(absValue / 1000);
        long remainder = (long)(absValue % 1000);

        // Check if we need millions breakdown
        if (thousands > 999) {
            long millions = thousands / 1000;
            long thousandsRemainder = thousands % 1000;

            // Generate millions * 1,000,000
            String millionsPattern = convertToAngleSignature(millions * (isNegative ? -1 : 1));
            String millionPattern = convertToAngleSignature(1000000.0);

            if (millionsPattern != null && millionPattern != null) {
                components.add(new PatternSequence.PatternComponent(
                        millionsPattern,
                        HexDir.NORTH_EAST,
                        "numerical_reflection_" + (millions * (isNegative ? -1 : 1))
                ));
                components.add(new PatternSequence.PatternComponent(
                        millionPattern,
                        HexDir.NORTH_EAST,
                        "numerical_reflection_1000000"
                ));
                components.add(new PatternSequence.PatternComponent(
                        MULTIPLICATIVE_DISTILLATION,
                        MULTIPLICATIVE_START,
                        "multiplicative_distillation"
                ));

                // Handle thousands remainder if present
                if (thousandsRemainder > 0) {
                    String thousandsPattern = convertToAngleSignature((double)thousandsRemainder);
                    String thousandPattern = convertToAngleSignature(1000.0);

                    if (thousandsPattern != null && thousandPattern != null) {
                        components.add(new PatternSequence.PatternComponent(
                                thousandsPattern,
                                HexDir.NORTH_EAST,
                                "numerical_reflection_" + thousandsRemainder
                        ));
                        components.add(new PatternSequence.PatternComponent(
                                thousandPattern,
                                HexDir.NORTH_EAST,
                                "numerical_reflection_1000"
                        ));
                        components.add(new PatternSequence.PatternComponent(
                                MULTIPLICATIVE_DISTILLATION,
                                MULTIPLICATIVE_START,
                                "multiplicative_distillation"
                        ));
                        // Combine millions and thousands
                        components.add(new PatternSequence.PatternComponent(
                                isNegative ? SUBTRACTIVE_DISTILLATION : ADDITIVE_DISTILLATION,
                                isNegative ? SUBTRACTIVE_START : HexDir.EAST,
                                isNegative ? "subtractive_distillation" : "additive_distillation"
                        ));
                    }
                }

                // Handle final remainder if present
                if (remainder > 0) {
                    String remainderPattern = convertToAngleSignature((double)remainder);
                    if (remainderPattern != null) {
                        components.add(new PatternSequence.PatternComponent(
                                remainderPattern,
                                HexDir.NORTH_EAST,
                                "numerical_reflection_" + remainder
                        ));
                        components.add(new PatternSequence.PatternComponent(
                                isNegative ? SUBTRACTIVE_DISTILLATION : ADDITIVE_DISTILLATION,
                                isNegative ? SUBTRACTIVE_START : HexDir.EAST,
                                isNegative ? "subtractive_distillation" : "additive_distillation"
                        ));
                    }
                }

                return components;
            }
        } else {
            // Simple thousands case: thousands * 1000 + remainder
            String thousandsPattern = convertToAngleSignature(thousands * (isNegative ? -1 : 1));
            String thousandPattern = convertToAngleSignature(1000.0);

            if (thousandsPattern != null && thousandPattern != null) {
                components.add(new PatternSequence.PatternComponent(
                        thousandsPattern,
                        HexDir.NORTH_EAST,
                        "numerical_reflection_" + (thousands * (isNegative ? -1 : 1))
                ));
                components.add(new PatternSequence.PatternComponent(
                        thousandPattern,
                        HexDir.NORTH_EAST,
                        "numerical_reflection_1000"
                ));
                components.add(new PatternSequence.PatternComponent(
                        MULTIPLICATIVE_DISTILLATION,
                        MULTIPLICATIVE_START,
                        "multiplicative_distillation"
                ));

                if (remainder > 0) {
                    String remainderPattern = convertToAngleSignature((double)remainder);
                    if (remainderPattern != null) {
                        components.add(new PatternSequence.PatternComponent(
                                remainderPattern,
                                HexDir.NORTH_EAST,
                                "numerical_reflection_" + remainder
                        ));
                        components.add(new PatternSequence.PatternComponent(
                                isNegative ? SUBTRACTIVE_DISTILLATION : ADDITIVE_DISTILLATION,
                                isNegative ? SUBTRACTIVE_START : HexDir.EAST,
                                isNegative ? "subtractive_distillation" : "additive_distillation"
                        ));
                    }
                }

                return components;
            }
        }

        return null;
    }

    // Keep these helper methods unchanged
    private static void addCombineOperation(PatternSequence sequence, boolean isNegative) {
        sequence.addOperation(
                isNegative ? SUBTRACTIVE_DISTILLATION : ADDITIVE_DISTILLATION,
                isNegative ? SUBTRACTIVE_START : HexDir.EAST,
                isNegative ? "subtractive_distillation" : "additive_distillation"
        );
    }

    // =============

    public static Angle charToAngle(char c) {
        switch(c) {
            case 'w': return Angle.FORWARD;
            case 'q': return Angle.LEFT;
            case 'e': return Angle.RIGHT;
            case 'a': return Angle.LEFT_BACK;
            case 'd': return Angle.RIGHT_BACK;
            default: throw new IllegalArgumentException("Invalid angle char: " + c);
        }
    }

    static class CustomSolver {
        private final double target;
        private final boolean negative;
        private final double absTarget;
        private int attemptCount = 0;
        private static final int MAX_ATTEMPTS = 200000; // prevent idiots from generating super big numbers

        CustomSolver(double target) {
            this.target = target;
            this.negative = target < 0;
            this.absTarget = Math.abs(target);
        }

        // In CustomSolver.generate() method, add validation before returning:
        Path generate() {
            Path initial = Path.zero(negative);
            if (absTarget == 0) return initial;

            Set<String> triedBreakdowns = new HashSet<>();
            int lastSaveAttempt = 0;

            while (attemptCount < MAX_ATTEMPTS) {
                List<Integer> operations = generateBreakdown(absTarget, triedBreakdowns, initial);

                if (operations == null) {
                    if (triedBreakdowns.size() > 50) {
                        triedBreakdowns.clear();
                    }
                    continue;
                }

                String breakdownKey = operations.toString();
                triedBreakdowns.add(breakdownKey);

                attemptCount++;

                Path result = executeSequence(initial, operations);

                if (result != null && Math.abs(result.value - absTarget) < 0.001) {
                    if (validateFullPattern(result.pattern)) {
                        if (ValidationConstants.DEBUG) {System.out.println("Generated pattern for " + target + " after " + attemptCount + " attempts");}
                        return result;
                    } else {
                        identifyAndRecordCollision(initial, operations);
                    }
                } else if (result == null) {
                    identifyAndRecordCollision(initial, operations);
                }
            }
            return null;
        }

        // Add this validation method to CustomSolver:
        private boolean validateFullPattern(String pattern) {
            Set<Long> edges = new HashSet<>();
            Coord pos = new Coord(0, 0);
            Direction dir = pattern.startsWith("dedd") ? Direction.SOUTHEAST : Direction.NORTHEAST;

            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                Direction nextDir = dir.turn(charToAngle(c));
                Coord nextPos = pos.move(nextDir);

                long edge = Path.encodeEdge(pos.x, pos.y, nextPos.x, nextPos.y);

                if (!edges.add(edge)) {
                    System.out.println("Pattern self-intersects at index " + i + " (char: " + c + ")");
                    return false;
                }

                dir = nextDir;
                pos = nextPos;
            }

            return true;
        }

        private void recordFailedSequence(List<Integer> operations, int startIdx, int endIdx) {
            // Record the operation sequence that caused collision
            if (endIdx > startIdx) {
                List<Integer> badSequence = operations.subList(startIdx, endIdx);
                String key = badSequence.toString();
                failedParts.computeIfAbsent("sequences", k -> new HashSet<>()).add(key);
            }
        }

        // In identifyAndRecordCollision, find the minimal problematic sequence:
        private void identifyAndRecordCollision(Path initial, List<Integer> operations) {
            Path current = initial;
            List<Integer> executedOps = new ArrayList<>();

            for (int i = 0; i < operations.size(); i++) {
                int op = operations.get(i);

                if (op == -2) {
                    Path next = current.tryWithAngle(Angle.LEFT_BACK, op);
                    if (next == null) {
                        executedOps.add(-2);
                        recordFailedSequence(executedOps);
                        return;
                    }
                    executedOps.add(-2);
                    current = next;
                } else if (op > 0) {
                    int remaining = op;

                    while (remaining >= 10) {
                        Path next = current.tryWithAngle(Angle.RIGHT, 10);
                        if (next == null) {
                            executedOps.add(10);
                            recordFailedSequence(executedOps);
                            return;
                        }
                        executedOps.add(10);
                        current = next;
                        remaining -= 10;
                    }

                    while (remaining >= 5) {
                        Path next = current.tryWithAngle(Angle.LEFT, 5);
                        if (next == null) {
                            executedOps.add(5);
                            recordFailedSequence(executedOps);
                            return;
                        }
                        executedOps.add(5);
                        current = next;
                        remaining -= 5;
                    }

                    while (remaining > 0) {
                        Path next = current.tryWithAngle(Angle.FORWARD, 1);
                        if (next == null) {
                            executedOps.add(1);
                            recordFailedSequence(executedOps);
                            return;
                        }
                        executedOps.add(1);
                        current = next;
                        remaining -= 1;
                    }
                }
            }
        }

        private void recordFailedSequence(List<Integer> sequence) {
            // This should now only contain 1, 5, 10, or -2
            String key = sequence.toString();
            failedParts.computeIfAbsent("sequences", k -> new HashSet<>()).add(key);
        }

        private List<Integer> generateBreakdown(double target, Set<String> triedBreakdowns, Path initial) {
            Random rand = new Random(System.currentTimeMillis() + attemptCount);

            // Try multiple strategies
            for (int strategy = 0; strategy < 5; strategy++) {
                List<Integer> operations = new ArrayList<>();
                double current = target;
                int depth = 0;
                boolean valid = true;

                // Simulate the path to check for collisions
                Path simPath = Path.zero(negative);

                while (current > 0.001 && depth < 30) {
                    depth++;

                    if (current <= 30) {
                        // Small enough to add directly - but check if it would work
                        Path testPath = addValue(simPath, (int)Math.round(current));
                        if (testPath != null) {
                            operations.add(0, (int)Math.round(current));
                            current = 0;
                            simPath = testPath;
                            break;
                        } else {
                            // Can't add this value, try a different strategy
                            valid = false;
                            break;
                        }
                    }

                    // Strategy variations
                    boolean useDouble = false;
                    int addAmount = 0;

                    if (strategy == 0) {
                        // Pure doubling when possible
                        if (current % 2 < 0.001 && countRecentDoubles(operations) < 2) {
                            // Test if double would work
                            Path testDouble = simPath.tryWithAngle(Angle.LEFT_BACK, -2);
                            if (testDouble != null) {
                                useDouble = true;
                                simPath = testDouble;
                            }
                        } else {
                            // Make it even
                            addAmount = (current % 2 == 0) ? 0 : 1;
                            if (addAmount > 0) {
                                Path testAdd = addValue(simPath, addAmount);
                                if (testAdd != null) {
                                    current -= addAmount;
                                    simPath = testAdd;
                                } else {
                                    valid = false;
                                    break;
                                }
                            }
                            if (current > 0) {
                                Path testDouble = simPath.tryWithAngle(Angle.LEFT_BACK, -2);
                                if (testDouble != null) {
                                    useDouble = true;
                                    simPath = testDouble;
                                } else {
                                    valid = false;
                                    break;
                                }
                            }
                        }
                    } else if (strategy == 1) {
                        // Prefer adding 1-10 to make even
                        for (int add = 1; add <= 10; add++) {
                            if ((current - add) % 2 < 0.001 && current - add > 0) {
                                Path testAdd = addValue(simPath, add);
                                if (testAdd != null) {
                                    addAmount = add;
                                    current -= add;
                                    simPath = testAdd;

                                    Path testDouble = simPath.tryWithAngle(Angle.LEFT_BACK, -2);
                                    if (testDouble != null) {
                                        useDouble = true;
                                        simPath = testDouble;
                                    } else {
                                        valid = false;
                                    }
                                    break;
                                }
                            }
                        }
                    } else if (strategy == 2) {
                        // Random additions
                        int maxAdd = Math.min(20, (int)(current / 2));
                        if (maxAdd > 0) {
                            addAmount = rand.nextInt(maxAdd) + 1;
                            if ((current - addAmount) % 2 < 0.001) {
                                Path testAdd = addValue(simPath, addAmount);
                                if (testAdd != null) {
                                    current -= addAmount;
                                    simPath = testAdd;
                                    useDouble = true;

                                    Path testDouble = simPath.tryWithAngle(Angle.LEFT_BACK, -2);
                                    if (testDouble != null) {
                                        simPath = testDouble;
                                    } else {
                                        useDouble = false;
                                        valid = false;
                                        break;
                                    }
                                }
                            }
                        }
                    } else {
                        // Mixed approach
                        if (rand.nextBoolean() && current % 2 < 0.001) {
                            Path testDouble = simPath.tryWithAngle(Angle.LEFT_BACK, -2);
                            if (testDouble != null) {
                                useDouble = true;
                                simPath = testDouble;
                            }
                        } else {
                            List<Integer> validAdds = new ArrayList<>();
                            for (int i = 1; i <= 20 && i < current; i++) {
                                if ((current - i) % 2 < 0.001) {
                                    Path testAdd = addValue(simPath, i);
                                    if (testAdd != null) {
                                        validAdds.add(i);
                                    }
                                }
                            }
                            if (!validAdds.isEmpty()) {
                                addAmount = validAdds.get(rand.nextInt(validAdds.size()));
                                Path testAdd = addValue(simPath, addAmount);
                                if (testAdd != null) {
                                    current -= addAmount;
                                    simPath = testAdd;

                                    Path testDouble = simPath.tryWithAngle(Angle.LEFT_BACK, -2);
                                    if (testDouble != null) {
                                        useDouble = true;
                                        simPath = testDouble;
                                    } else {
                                        valid = false;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (useDouble && current > 0) {
                        // Check if this would create a known bad sequence
                        List<Integer> recentOps = new ArrayList<>();
                        int lookback = Math.min(5, operations.size());
                        for (int j = lookback; j > 0; j--) {
                            recentOps.add(operations.get(j - 1));
                        }
                        if (addAmount > 0) recentOps.add(addAmount);
                        recentOps.add(-2);

                        String checkKey = recentOps.toString();
                        Set<String> badSequences = failedParts.get("sequences");
                        if (badSequences != null && badSequences.contains(checkKey)) {
                            valid = false; // Skip this strategy, it creates a known bad pattern
                            break;
                        }

                        // Pattern check passed, proceed with adding the operations
                        if (addAmount > 0) operations.add(0, addAmount);
                        operations.add(0, -2);
                        current = current / 2;
                    } else if (!useDouble) {
                        // Can't proceed with this strategy
                        valid = false;
                        break;
                    }
                }

                if (valid && current < 0.001 && !triedBreakdowns.contains(operations.toString())) {
                    return operations;
                }
            }

            return null;
        }

        private int countRecentDoubles(List<Integer> ops) {
            int count = 0;
            for (int op : ops) {
                if (op == -2) {
                    count++;
                    if (count >= 2) return count;
                } else if (op > 0) {
                    count = 0;
                }
            }
            return count;
        }

        private Path executeSequence(Path start, List<Integer> operations) {
            Path current = start;

            for (int op : operations) {
                if (op == -2) {
                    Path doubled = current.tryWithAngle(Angle.LEFT_BACK, op);
                    if (doubled == null) return null;
                    current = doubled;
                } else if (op > 0) {
                    current = addValue(current, op);
                    if (current == null) return null;
                }
            }

            return current;
        }

        private Path addValue(Path current, int value) {
            int remaining = value;

            while (remaining >= 10) {
                Path next = current.tryWithAngle(Angle.RIGHT, 10);
                if (next == null) return null;
                current = next;
                remaining -= 10;
            }

            while (remaining >= 5) {
                Path next = current.tryWithAngle(Angle.LEFT, 5);
                if (next == null) return null;
                current = next;
                remaining -= 5;
            }

            while (remaining > 0) {
                Path next = current.tryWithAngle(Angle.FORWARD, 1);
                if (next == null) return null;
                current = next;
                remaining -= 1;
            }

            return current;
        }
    }

    // Main generation method
    private static Path generatePattern(double target) {
        CustomSolver generator = new CustomSolver(target);
        Path result = generator.generate();

        if (result == null) {
            System.out.println("=== Failed to generate pattern for " + target + " ===");
        }

        return result;
    }

    // Main conversion method with caching
    public static String convertToAngleSignature(double target) {
        // Check if it's a decimal number
        if (target % 1 != 0) {
            // Decimals are handled by convertToPatternSequence
            return null;
        }

        if (USE_CACHE) {
            if (!cacheLoaded) {
                loadCache();
            }
            String cached = patternCache.get(target);
            if (cached != null) {
                if (validateCachedPattern(cached)) {
                    return cached;
                } else {
                    System.out.println("Cached pattern was invalid, regenerating...");
                    patternCache.remove(target);
                }
            }
        }

        Path result = generatePattern(target);

        if (result != null) {
            if (validateCachedPattern(result.pattern)) {
                if (USE_CACHE) {
                    patternCache.put(target, result.pattern);
                    saveCache();
                }
                return result.pattern;
            } else {
                System.out.println("Generated invalid pattern, not caching");
                return null;
            }
        }

        return null;
    }

    private static boolean validateCombinedPattern(String pattern) {
        // Basic validation - just check it's not empty and has valid characters
        if (pattern == null || pattern.isEmpty()) return false;

        for (char c : pattern.toCharArray()) {
            if (c != 'w' && c != 'q' && c != 'e' && c != 'a' && c != 'd') {
                return false;
            }
        }

        return true;
    }

    // Add this helper method:
    private static boolean validateCachedPattern(String pattern) {
        Set<Long> edges = new HashSet<>();
        Coord pos = new Coord(0, 0);
        Direction dir = pattern.startsWith("dedd") ? Direction.SOUTHEAST : Direction.NORTHEAST;

        for (char c : pattern.toCharArray()) {
            Direction nextDir = dir.turn(charToAngle(c));
            Coord nextPos = pos.move(nextDir);

            long edge = Path.encodeEdge(pos.x, pos.y, nextPos.x, nextPos.y);

            if (!edges.add(edge)) {
                return false;
            }

            dir = nextDir;
            pos = nextPos;
        }

        return true;
    }

    // Cache management methods remain the same...
    private static java.nio.file.Path getPatternsDir() {
        return HexCastingPlusClientConfig.getPatternsDirectory();
    }

    private static void loadCache() {
        cacheLoaded = true;
        java.nio.file.Path cacheFile = getPatternsDir().resolve(HexCastingPlusClientConfig.NUMBER_CACHE_FILE);
        if (!Files.exists(cacheFile)) {
            System.out.println("Cache file does not exist: " + cacheFile);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    try {
                        double number = Double.parseDouble(parts[0].trim());
                        String pattern = parts[1].trim();
                        patternCache.put(number, pattern);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid number at line " + lineNum + ": " + parts[0]);
                    }
                }
            }
            System.out.println("Loaded " + patternCache.size() + " patterns from cache");
        } catch (IOException e) {
            System.err.println("Error loading cache: " + e.getMessage());
        }
    }

    private static void saveCache() {
        java.nio.file.Path cacheFile = getPatternsDir().resolve(HexCastingPlusClientConfig.NUMBER_CACHE_FILE);
        try {
            Files.createDirectories(getPatternsDir());
            try (BufferedWriter writer = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8)) {
                writer.write("# Hexcasting Number Pattern Cache\n");
                writer.write("# Format: number,pattern\n");
                writer.write("# Sorted from highest to lowest\n");

                for (Map.Entry<Double, String> entry : patternCache.entrySet()) {
                    String number = entry.getKey() % 1 == 0 ?
                            String.format("%.0f", entry.getKey()) :
                            String.valueOf(entry.getKey());
                    writer.write(number + "," + entry.getValue() + "\n");
                }
            }
            System.out.println("Saved " + patternCache.size() + " patterns to cache");
        } catch (IOException e) {
            System.err.println("Error saving cache: " + e.getMessage());
        }
    }


}