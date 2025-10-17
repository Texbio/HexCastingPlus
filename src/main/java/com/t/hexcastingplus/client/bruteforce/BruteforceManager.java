package com.t.hexcastingplus.client.bruteforce;

import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import com.t.hexcastingplus.client.greatspells.GreatSpellCache;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.math.EulerPathFinder;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.t.hexcastingplus.client.gui.PatternDrawingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;


import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class BruteforceManager {
    private static BruteforceManager instance;

    // Configuration
    private static final int MAX_SEEDS = 200000;
    private static final long MATCH_WAIT_TIME = 500;
    private long currentSeedOffset = 0;

    // State tracking
    private static boolean isBruteforcing = false;
    private int bruteforceAttempts = 0;
    private String targetPatternName = null;

    // Pattern management
    private List<ResourceKey<ActionRegistryEntry>> perWorldPatterns = null;
    private int currentPatternIndex = 0;
    private HexPattern currentBruteforcePattern = null;
    private String currentBruteforceName = "";

    // Signature processing
    private Set<String> currentPatternSignatures = new HashSet<>();
    private Iterator<String> signatureIterator = null;
    private String currentSignature = null;
    private int signaturesTriedCount = 0;
    private int totalSignaturesCount = 0;

    // Response tracking
    private boolean awaitingResponse = false;
    private long lastAttemptTime = 0;
    private Queue<SentPattern> sentPatternQueue = new LinkedList<>();
    private int sequenceCounter = 0;
    private static final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    // Match processing
    private boolean foundMatch = false;
    private long matchFoundTime = 0;
    private List<Integer> successfulSequences = new ArrayList<>();

    // Stack management
    private boolean clearingStack = false;
    private int patternsSentSinceClean = 0;

    // Cache management
    private Map<String, String> foundPatternsCache = new HashMap<>();
    private String cacheFilePath = null;
    private boolean cacheLoaded = false;

    // GUI references
    private GuiSpellcasting currentStaffGui;
    private Button bruteforceButton;

    private static class SentPattern {
        final String signature;
        final long sentTime;
        final int sequenceNumber;
        final String patternName;

        SentPattern(String signature, long sentTime, int sequenceNumber, String patternName) {
            this.signature = signature;
            this.sentTime = sentTime;
            this.sequenceNumber = sequenceNumber;
            this.patternName = patternName;
        }
    }

    private BruteforceManager() {}

    public String getSolvedSignature(String patternName) {
        loadCache(); // Ensure cache is loaded
        String signature = foundPatternsCache.get(patternName);
        if (signature == null && !patternName.startsWith("hexcasting:")) {
            signature = foundPatternsCache.get("hexcasting:" + patternName);
        }
        return signature;
    }

    public HexPattern signatureToPattern(String angleSignature) {
        if (angleSignature == null || !angleSignature.contains(",")) {
            return null;
        }

        try {
            String[] parts = angleSignature.split(",", 2);
            if (parts.length != 2) return null;

            String angles = parts[0];
            String dirName = parts[1];

            HexDir startDir = HexDir.valueOf(dirName);
            return HexPattern.fromAngles(angles, startDir);
        } catch (Exception e) {
            System.err.println("[HexCasting+] Bruteforce - Failed to convert signature to pattern: " + e.getMessage());
            return null;
        }
    }

    public void reloadCache() {
        cacheLoaded = false;
        loadCache();
    }

    public void ensureCacheLoaded() {
        if (!cacheLoaded) {
            loadCache();
        }
    }

    public static BruteforceManager getInstance() {
        if (instance == null) {
            instance = new BruteforceManager();
        }
        return instance;
    }

    public void setStaffGui(GuiSpellcasting gui) {
        this.currentStaffGui = gui;
    }

    public void setBruteforceButton(Button button) {
        this.bruteforceButton = button;
    }

    public static boolean isBruteforcing() {
        return isBruteforcing;
    }

    public void startBruteforce() {
        if (isBruteforcing) {
            stopBruteforce();
            return;
        }

        loadCache();

        var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        perWorldPatterns = new ArrayList<>();

        if (targetPatternName != null) {
            // Targeted bruteforce logic
            for (var key : registry.registryKeySet()) {
                try {
                    String patternName = key.location().toString();
                    if (patternName.equals(targetPatternName)) {
                        if (registry.getHolderOrThrow(key).is(HexTags.Actions.PER_WORLD_PATTERN)) {
                            if (!foundPatternsCache.containsKey(patternName)) {
                                perWorldPatterns.add(key);
                                System.out.println("[HexCasting+] Bruteforce - Targeting: " + patternName);
                            } else {
                                System.out.println("[HexCasting+] Bruteforce - Pattern already solved: " + patternName);
                                reloadCache();
                            }
                        }
                        break;
                    }
                } catch (Exception e) {
                    // Skip if holder check fails
                }
            }
        } else {
            // Full bruteforce - add all unsolved patterns
            for (var key : registry.registryKeySet()) {
                try {
                    if (registry.getHolderOrThrow(key).is(HexTags.Actions.PER_WORLD_PATTERN)) {
                        String patternName = key.location().toString();
                        if (!foundPatternsCache.containsKey(patternName)) {
                            perWorldPatterns.add(key);
                        }
                    }
                } catch (Exception e) {
                    // Skip
                }
            }

            // Sort patterns by complexity
            sortPatternsByComplexity();
        }

        if (perWorldPatterns.isEmpty()) {
            if (targetPatternName != null) {
                System.out.println("[HexCasting+] Bruteforce - Target pattern already solved or not found");
            } else {
                System.out.println("[HexCasting+] Bruteforce - All patterns already found in cache or no patterns available");
            }
            targetPatternName = null;
            return;
        }

        System.out.println("[HexCasting+] Bruteforce - Starting - " + perWorldPatterns.size() + " pattern(s) to find");
        System.out.println("[HexCasting+] Bruteforce - " + foundPatternsCache.size() + " patterns already cached");

        isBruteforcing = true;
        bruteforceAttempts = 0;
        currentPatternIndex = 0;
        awaitingResponse = false;

        if (bruteforceButton != null) {
            bruteforceButton.setMessage(Component.literal("Stop"));
        }

        loadCurrentPattern();
    }

    public void bruteforceSpecificPattern(String patternName) {
        if (isBruteforcing) {
            System.out.println("[HexCasting+] Bruteforce - Already running, cannot start targeted bruteforce");
            return;
        }

        System.out.println("[HexCasting+] Bruteforce - Starting targeted bruteforce for: " + patternName);
        targetPatternName = patternName;
        startBruteforce();
    }

    public void stopBruteforce() {
        isBruteforcing = false;
        awaitingResponse = false;

        if (bruteforceButton != null) {
            bruteforceButton.setMessage(Component.literal("B"));
        }

        System.out.println("[HexCasting+] Bruteforce - Stopped");
        System.out.println("  Total attempts: " + bruteforceAttempts);
        System.out.println("  Total patterns cached: " + foundPatternsCache.size());

        currentPatternSignatures.clear();
        signatureIterator = null;
        currentSignature = null;

        if (targetPatternName != null) {
            System.out.println("[HexCasting+] Bruteforce - Clearing target pattern: " + targetPatternName);
            targetPatternName = null;
            reloadCache();
        }

        // Delay the stack clear by 500ms
        scheduler.schedule(() -> {
            Minecraft.getInstance().execute(() -> {
                clearStacks(true);
            });
        }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void tick() {
        if (!isBruteforcing) return;

        if (!(Minecraft.getInstance().screen instanceof GuiSpellcasting)) {
            stopBruteforce();
            return;
        }

        if (foundMatch) {
            long timeSinceMatch = System.currentTimeMillis() - matchFoundTime;
            if (timeSinceMatch >= MATCH_WAIT_TIME) {
                processMatchResults();
            }
            return;
        }

        if (clearingStack) {
            return;
        }

        if (awaitingResponse) {
            long timeSinceAttempt = System.currentTimeMillis() - lastAttemptTime;
            long timeout = 1000;

            if (timeSinceAttempt > timeout) {
                awaitingResponse = false;
                tryNextSignature();
            }
            return;
        }

        tryNextSignature();
    }

    public void handleServerResponse(ResolvedPatternType resolutionType) {
        awaitingResponse = false;

        if (clearingStack) {
            clearingStack = false;
            return;
        }

        if (resolutionType == ResolvedPatternType.EVALUATED ||
                resolutionType == ResolvedPatternType.ERRORED) {

            if (!foundMatch) {
                foundMatch = true;
                matchFoundTime = System.currentTimeMillis();
                System.out.println("[HexCasting+] Bruteforce - Match found! Waiting for delayed responses...");
            }

            successfulSequences.add(sequenceCounter);

        } else if (resolutionType == ResolvedPatternType.INVALID) {
            if (!foundMatch) {
                tryNextSignature();
            }
        } else {
            if (!foundMatch) {
                tryNextSignature();
            }
        }
    }

    private void sortPatternsByComplexity() {
        System.out.println("[HexCasting+] Bruteforce - Analyzing pattern complexity...");
        Map<ResourceKey<ActionRegistryEntry>, Integer> patternComplexity = new HashMap<>();
        var registry = IXplatAbstractions.INSTANCE.getActionRegistry();

        for (var key : perWorldPatterns) {
            var entry = registry.get(key);
            if (entry != null && entry.prototype() != null) {
                Set<String> uniqueSignatures = new HashSet<>();
                HexPattern prototype = entry.prototype();

                String originalSig = patternToAngleSignature(prototype);
                if (originalSig != null) {
                    uniqueSignatures.add(originalSig);
                }

                for (long seed = 0; seed < 1000; seed++) {
                    try {
                        HexPattern variant = EulerPathFinder.findAltDrawing(prototype, seed);
                        String variantSig = patternToAngleSignature(variant);
                        if (variantSig != null) {
                            uniqueSignatures.add(variantSig);
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                }

                patternComplexity.put(key, uniqueSignatures.size());
                System.out.println("  " + key.location().toString() + ": ~" +
                        (uniqueSignatures.size() * 10) + " estimated variants");
            }
        }

        perWorldPatterns.sort((a, b) -> {
            Integer complexityA = patternComplexity.getOrDefault(a, Integer.MAX_VALUE);
            Integer complexityB = patternComplexity.getOrDefault(b, Integer.MAX_VALUE);
            return complexityA.compareTo(complexityB);
        });
    }

    private void loadCurrentPattern() {
        if (currentPatternIndex >= perWorldPatterns.size()) {
            System.out.println("[HexCasting+] Bruteforce - Complete - all patterns processed");

            // Delay clearing the stack by 500ms
            scheduler.schedule(() -> {
                Minecraft.getInstance().execute(() -> {
                    clearStacks(true);
                });
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);

            stopBruteforce();
            return;
        }


        var key = perWorldPatterns.get(currentPatternIndex);
        var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        var entry = registry.get(key);

        if (entry != null && entry.prototype() != null) {
            currentBruteforcePattern = entry.prototype();
            currentBruteforceName = key.location().toString();

            generateUniqueSignatures();
            signaturesTriedCount = 0;

            System.out.println("[HexCasting+] Bruteforce - Testing: " + currentBruteforceName +
                    " with " + totalSignaturesCount + " unique signatures");

            tryNextSignature();
        } else {
            currentPatternIndex++;
            loadCurrentPattern();
        }
    }

    private void generateUniqueSignatures() {
        currentPatternSignatures.clear();
        signaturesTriedCount = 0;

        // Start with a smaller number and increase if needed
        int initialSeeds = 10000; // Start with 10k instead of 200k
        int maxBatches = 20; // Max 20 batches of 10k each
        int batchSize = 10000;

        // Generate initial batch
        for (long seed = 0; seed < initialSeeds; seed++) {
            try {
                HexPattern variant = EulerPathFinder.findAltDrawing(currentBruteforcePattern, seed);
                String signature = variant.anglesSignature() + "," + variant.getStartDir().name();
                currentPatternSignatures.add(signature);
            } catch (Exception e) {
                // Skip failed generations
            }
        }

        totalSignaturesCount = currentPatternSignatures.size();
        signatureIterator = currentPatternSignatures.iterator();

        System.out.println("[BRUTEFORCE] Generated " + totalSignaturesCount +
                " unique signatures from " + initialSeeds + " seeds");

        // Store the seed offset for potential batch generation
        currentSeedOffset = initialSeeds;
    }

    private void generateAdditionalSignatures() {
        if (currentSeedOffset >= MAX_SEEDS) {
            System.out.println("[BRUTEFORCE] Reached maximum seed limit");
            return;
        }

        int batchSize = 10000;
        long endSeed = Math.min(currentSeedOffset + batchSize, MAX_SEEDS);

        Set<String> newSignatures = new HashSet<>();
        for (long seed = currentSeedOffset; seed < endSeed; seed++) {
            try {
                HexPattern variant = EulerPathFinder.findAltDrawing(currentBruteforcePattern, seed);
                String signature = variant.anglesSignature() + "," + variant.getStartDir().name();
                if (!currentPatternSignatures.contains(signature)) {
                    newSignatures.add(signature);
                }
            } catch (Exception e) {
                // Skip failed generations
            }
        }

        currentPatternSignatures.addAll(newSignatures);
        totalSignaturesCount = currentPatternSignatures.size();
        signatureIterator = currentPatternSignatures.iterator();
        currentSeedOffset = endSeed;

        System.out.println("[BRUTEFORCE] Generated " + newSignatures.size() +
                " additional unique signatures (total: " + totalSignaturesCount + ")");
    }

    private void tryNextSignature() {
        if (foundMatch) {
            long timeSinceMatch = System.currentTimeMillis() - matchFoundTime;
            if (timeSinceMatch < MATCH_WAIT_TIME) {
                return;
            } else {
                processMatchResults();
                return;
            }
        }

        if (patternsSentSinceClean >= 10 && !clearingStack) {
            clearingStack = true;
            clearStacks(false);
            patternsSentSinceClean = 0;
            return;
        }

        if (!signatureIterator.hasNext()) {
            // Try to generate more signatures before giving up
            if (currentSeedOffset < MAX_SEEDS && currentPatternSignatures.size() < 50000) {
                System.out.println("[BRUTEFORCE] Generating additional signatures...");
                generateAdditionalSignatures();

                // If we got new signatures, continue
                if (signatureIterator.hasNext()) {
                    // Continue with the new signatures
                } else {
                    System.out.println("[BRUTEFORCE] No new unique signatures found for " + currentBruteforceName);
                    moveToNextPattern();
                    return;
                }
            } else {
                System.out.println("[BRUTEFORCE] Exhausted all signatures for " + currentBruteforceName);
                moveToNextPattern();
                return;
            }
        }

        currentSignature = signatureIterator.next();
        signaturesTriedCount++;

        String[] parts = currentSignature.split(",", 2);
        if (parts.length != 2) {
            tryNextSignature();
            return;
        }

        String angles = parts[0];
        HexDir startDir;
        try {
            startDir = HexDir.valueOf(parts[1]);
        } catch (Exception e) {
            tryNextSignature();
            return;
        }

        HexPattern pattern = HexPattern.fromAngles(angles, startDir);

        sequenceCounter++;
        sentPatternQueue.offer(new SentPattern(
                currentSignature,
                System.currentTimeMillis(),
                sequenceCounter,
                currentBruteforceName
        ));

        long cutoffTime = System.currentTimeMillis() - 10000;
        while (!sentPatternQueue.isEmpty() && sentPatternQueue.peek().sentTime < cutoffTime) {
            sentPatternQueue.poll();
        }

        if (currentStaffGui != null) {
            PatternDrawingHelper.addPatternViaPacket(
                    currentStaffGui,
                    pattern,
                    currentBruteforceName
            );
        }

        lastAttemptTime = System.currentTimeMillis();
        awaitingResponse = true;
        bruteforceAttempts++;
        patternsSentSinceClean++;

        if (bruteforceButton != null) {
            bruteforceButton.setMessage(
                    Component.literal(String.valueOf(signaturesTriedCount))
                            .withStyle(style -> style.withFont(new ResourceLocation("minecraft", "uniform")))
            );
        }
    }

    private void processMatchResults() {
        if (successfulSequences.isEmpty()) {
            System.out.println("[HexCasting+] Bruteforce - No successful matches found, continuing...");
            foundMatch = false;
            tryNextSignature();
            return;
        }

        int earliestSequence = successfulSequences.stream()
                .min(Integer::compareTo)
                .orElse(-1);

        System.out.println("[HexCasting+] Bruteforce - Processing " + successfulSequences.size() +
                " successful responses, earliest sequence: " + earliestSequence);

        SentPattern matchedPattern = null;
        for (SentPattern sent : sentPatternQueue) {
            if (sent.sequenceNumber == earliestSequence &&
                    sent.patternName.equals(currentBruteforceName)) {
                matchedPattern = sent;
                break;
            }
        }

        if (matchedPattern != null) {
            if (validatePattern(matchedPattern)) {
                saveToCache(matchedPattern.patternName, matchedPattern.signature);
                System.out.println("[BRUTEFORCE SUCCESS] Found correct pattern: " +
                        matchedPattern.signature + " (sequence #" + earliestSequence + ")");

                cleanupAfterSuccess();
                moveToNextPattern();
            } else {
                System.out.println("[HexCasting+] Bruteforce - Pattern validation failed, may be Bookkeeper's Gambit, continuing...");
                foundMatch = false;
                successfulSequences.clear();
                tryNextSignature();
            }
        } else {
            System.out.println("[HexCasting+] Bruteforce - Could not find pattern for sequence #" + earliestSequence + ", continuing...");
            foundMatch = false;
            successfulSequences.clear();
            tryNextSignature();
        }
    }

    private void cleanupAfterSuccess() {
        foundMatch = false;
        successfulSequences.clear();
        sentPatternQueue.clear();
        awaitingResponse = false;
        clearingStack = false;
        patternsSentSinceClean = 0;
    }

    private boolean validatePattern(SentPattern pattern) {
        String sig = pattern.signature;
        if (sig.length() > 3) {
            char firstAngle = sig.charAt(1);
            boolean allSame = true;
            for (int i = 2; i < sig.length(); i++) {
                if (sig.charAt(i) != firstAngle) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                System.out.println("[HexCasting+] Bruteforce - Rejecting pattern - appears to be Bookkeeper's Gambit");
                return false;
            }
        }

        if (sig.length() < 4 || sig.length() > 50) {
            System.out.println("[HexCasting+] Bruteforce - Rejecting pattern - unusual length: " + sig.length());
            return false;
        }

        return true;
    }

    private void moveToNextPattern() {
        cleanupAfterSuccess();
        currentPatternIndex++;
        loadCurrentPattern();
    }

    public int getRemainingPatternsCount() {
        if (!isBruteforcing) {
            // Count unsolved patterns when not actively bruteforcing
            var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
            int unsolved = 0;

            for (var key : registry.registryKeySet()) {
                try {
                    if (registry.getHolderOrThrow(key).is(HexTags.Actions.PER_WORLD_PATTERN)) {
                        String patternName = key.location().toString();
                        if (!foundPatternsCache.containsKey(patternName)) {
                            unsolved++;
                        }
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
            return unsolved;
        } else {
            // When bruteforcing, return patterns left to process
            if (perWorldPatterns != null) {
                return perWorldPatterns.size() - currentPatternIndex;
            }
            return 0;
        }
    }

    public Component getButtonTooltip() {
        int remaining = getRemainingPatternsCount();
        return Component.literal("Bruteforce Great Spells: " + remaining + " left");
    }

    public boolean isBruteforceComplete() {
        if (!cacheLoaded) {
            loadCache();
        }
        return getRemainingPatternsCount() == 0;
    }

    private void clearStacks(boolean all) {
        if (currentStaffGui == null) {
            System.out.println("[HexCasting+] Bruteforce - clearStacks - no staff GUI available");
            return;
        }

        // Get the actual stack size through reflection
        List<CompoundTag> currentStack = getCachedStack();
        int stackSize = currentStack.size();

        if (stackSize == 0) {
            System.out.println("[HexCasting+] Bruteforce - clearStacks - stack empty, nothing to clear");
            return;
        }

        int toClear;
        if (all) {
            toClear = stackSize;
        } else {
            toClear = stackSize - 1; // Clear all but one item
            if (toClear <= 0) {
                System.out.println("[HexCasting+] Bruteforce - clearStacks - nothing to clear (toClear=" + toClear + ")");
                return;
            }
        }

        final HexDir START_DIR = HexDir.SOUTH_WEST;
        StringBuilder angles = new StringBuilder();

        for (int i = 0; i < toClear - 1; i++) {
            if (i % 2 == 0) {
                angles.append('a');
            } else {
                angles.append('d');
            }
        }

        if (toClear > 0) {
            HexPattern pattern = HexPattern.fromAngles(angles.toString(), START_DIR);
            PatternDrawingHelper.addPatternViaPacket(
                    currentStaffGui,
                    pattern,
                    "bookkeepers_gambit_clear"
            );
            clearingStack = true;
            awaitingResponse = true;
        }
    }

    @SuppressWarnings("unchecked")
    private List<CompoundTag> getCachedStack() {
        try {
            Field cachedStackField = currentStaffGui.getClass().getDeclaredField("cachedStack");
            cachedStackField.setAccessible(true);
            Object value = cachedStackField.get(currentStaffGui);

            if (value instanceof List) {
                return (List<CompoundTag>) value;
            }
        } catch (Exception e) {
            // Silent fail
        }
        return List.of();
    }

    private static String getCacheFileName() {
        Minecraft mc = Minecraft.getInstance();
        String fileName;

        if (mc.hasSingleplayerServer()) {
            try {
                var serverWorld = mc.getSingleplayerServer().overworld();

                // Use ONLY the world seed as the unique identifier
                long worldSeed = serverWorld.getSeed();

                // Convert to unsigned string to avoid negative numbers in filename
                String worldUUID = Long.toUnsignedString(worldSeed);

                fileName = "sp_" + worldUUID + ".csv";
            } catch (Exception e) {
                fileName = "sp_unknown.csv";
            }
        } else {
            // Multiplayer - use server address
            var connection = mc.getConnection();
            if (connection != null && connection.getConnection() != null) {
                String serverAddress = connection.getConnection().getRemoteAddress().toString();
                serverAddress = serverAddress.replace("/", "").replace(":", "_");
                fileName = "mp_" + sanitizeFileName(serverAddress) + ".csv";
            } else {
                fileName = "mp_unknown.csv";
            }
        }

        return fileName;
    }

    private void saveToCache(String patternName, String angleSignature) {
        try {
            // Use centralized config for path
            Path configDir = HexCastingPlusClientConfig.getModDirectory()
                    .resolve(HexCastingPlusClientConfig.GREAT_SPELLS_FOLDER);
            Files.createDirectories(configDir);

            String fileName = getCacheFileName();
            Path cacheFile = configDir.resolve(fileName);
            boolean fileExists = Files.exists(cacheFile);
            cacheFilePath = cacheFile.toString();

            // If file doesn't exist, create it with header comment
            if (!fileExists) {
                String worldName = "Unknown";
                try {
                    if (Minecraft.getInstance().hasSingleplayerServer()) {
                        var serverWorld = Minecraft.getInstance().getSingleplayerServer().overworld();
                        worldName = serverWorld.getSeed() + " - " +
                                Minecraft.getInstance().getSingleplayerServer().getWorldData().getLevelName();
                    } else {
                        var connection = Minecraft.getInstance().getConnection();
                        if (connection != null && connection.getConnection() != null) {
                            worldName = connection.getConnection().getRemoteAddress().toString();
                        }
                    }
                } catch (Exception e) {
                    // Keep "Unknown" as fallback
                }

                String header = "// " + worldName + "\n";
                Files.write(cacheFile, header.getBytes(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
            }

            // Append the new pattern
            String csvLine = patternName + "," + angleSignature + "\n";
            Files.write(Paths.get(cacheFilePath),
                    csvLine.getBytes(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);

            foundPatternsCache.put(patternName, angleSignature);
            System.out.println("[BRUTEFORCE SUCCESS] Saved: " + patternName + " -> " + angleSignature);

            reloadCache();
        } catch (Exception e) {
            System.err.println("[HexCasting+] Bruteforce - Failed to save to cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadCache() {
        if (cacheLoaded) return;

        try {
            // Use centralized config for path
            Path configDir = HexCastingPlusClientConfig.getModDirectory()
                    .resolve(HexCastingPlusClientConfig.GREAT_SPELLS_FOLDER);
            Files.createDirectories(configDir);

            String fileName = getCacheFileName();
            Path cacheFile = configDir.resolve(fileName);
            cacheFilePath = cacheFile.toString();

            if (Files.exists(cacheFile)) {
                List<String> lines = Files.readAllLines(cacheFile);
                for (String line : lines) {
                    // Skip comment lines
                    if (line.startsWith("//")) {
                        continue;
                    }

                    String[] parts = line.split(",", 2);
                    if (parts.length == 2) {
                        foundPatternsCache.put(parts[0].trim(), parts[1].trim());
                    }
                }
                System.out.println("[HexCasting+] Bruteforce - Loaded " + foundPatternsCache.size() + " cached patterns from " + fileName);
            } else {
                System.out.println("[HexCasting+] Bruteforce - No cache file found, starting fresh: " + fileName);
            }

            cacheLoaded = true;
        } catch (Exception e) {
            System.err.println("[HexCasting+] Bruteforce - Failed to load cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private String patternToAngleSignature(HexPattern pattern) {
        try {
            CompoundTag nbt = pattern.serializeToNBT();

            HexDir startDir = HexDir.EAST;
            if (nbt.contains("start_dir")) {
                byte startDirOrdinal = nbt.getByte("start_dir");
                startDir = HexDir.values()[startDirOrdinal];
            }

            StringBuilder signature = new StringBuilder();
            if (nbt.contains("angles")) {
                byte[] angles = nbt.getByteArray("angles");
                for (byte angle : angles) {
                    char angleChar = ordinalToAngleChar(angle);
                    if (angleChar != '?') {
                        signature.append(angleChar);
                    }
                }
            }

            return signature.toString() + "," + startDir.name();
        } catch (Exception e) {
            return null;
        }
    }

    private char ordinalToAngleChar(int ordinal) {
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
}