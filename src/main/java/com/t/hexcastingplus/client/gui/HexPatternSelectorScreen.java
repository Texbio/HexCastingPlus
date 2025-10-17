package com.t.hexcastingplus.client.gui;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.eval.SpecialPatterns;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import at.petrak.hexcasting.interop.pehkui.PehkuiInterop;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.t.hexcastingplus.client.bruteforce.BruteforceManager;
import com.t.hexcastingplus.client.config.HexCastingPlusClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Unique;
import at.petrak.hexcasting.api.mod.HexTags;
import com.t.hexcastingplus.common.pattern.PatternRotationUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public class HexPatternSelectorScreen extends Screen {
    private final GuiSpellcasting parentGui;
    private EditBox searchBox;
    private PatternListArea patternList;
    private Button closeButton;
    private Button addButton;
    private Button toggleMethodButton;
    private static double lastScrollPosition = 0;
    private static String lastSearchTerm = "";
    private static int lastSelectedIndex = -1;

    private static class ExtractedPattern {
        final String name;
        final String angleSignature;
        final HexDir startDir;
        final String category;
        final HexPattern pattern;
        final boolean isPerWorldPattern;
        final boolean isSolved;
        final String originalPatternName;

        ExtractedPattern(String name, HexPattern pattern, String category, boolean isPerWorldPattern, String resourceLocation) {
            this.name = name;
            this.category = category;
            this.isPerWorldPattern = isPerWorldPattern;
            this.originalPatternName = resourceLocation; // Store the full resource location

            // Check if this is a solved great spell
            if (isPerWorldPattern) {
                String solvedSignature = BruteforceManager.getInstance().getSolvedSignature(resourceLocation);
                if (solvedSignature != null) {
                    // Pattern is solved - use the cached version
                    this.isSolved = true;
                    HexPattern solvedPattern = BruteforceManager.getInstance().signatureToPattern(solvedSignature);
                    if (solvedPattern != null) {
                        this.pattern = solvedPattern;
                        this.angleSignature = solvedPattern.anglesSignature();
                        this.startDir = getStartDir(solvedPattern);
                    } else {
                        // Fallback if conversion fails
                        this.pattern = pattern;
                        this.angleSignature = pattern.anglesSignature();
                        this.startDir = getStartDir(pattern);
                    }
                } else {
                    // Pattern is not solved - use original
                    this.isSolved = false;
                    this.pattern = pattern;
                    this.angleSignature = pattern.anglesSignature();
                    this.startDir = getStartDir(pattern);
                }
            } else {
                // Not a per-world pattern
                this.isSolved = false; // Not applicable, but set to false
                this.pattern = pattern;
                this.angleSignature = pattern.anglesSignature();
                this.startDir = getStartDir(pattern);
            }
        }

        // Keep the existing getStartDir method unchanged
        private static HexDir getStartDir(HexPattern pattern) {
            return PatternRotationUtil.getStartDir(pattern);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Pattern list area will update its smooth scrolling in render()
        // No need to explicitly call anything here
    }

    private List<ExtractedPattern> allPatterns = new ArrayList<>();
    private List<ExtractedPattern> filteredPatterns = new ArrayList<>();
    private int selectedIndex = -1;

    public HexPatternSelectorScreen(GuiSpellcasting parentGui) {
        super(Component.literal("Hex Pattern Library"));
        this.parentGui = parentGui;
        extractPatternsFromHexActions();
    }

    private void extractPatternsFromHexActions() {
        try {
            // Get the action registry through IXplatAbstractions
            var registry = IXplatAbstractions.INSTANCE.getActionRegistry();

            // Track which patterns we successfully matched
            Set<String> matchedPatterns = new HashSet<>();

            // Iterate through all registered entries
            for (var entry : registry.entrySet()) {
                ResourceKey<ActionRegistryEntry> resourceKey = entry.getKey();
                ActionRegistryEntry actionEntry = entry.getValue();

                if (actionEntry == null || actionEntry.prototype() == null) {
                    continue;
                }

                HexPattern pattern = actionEntry.prototype();
                ResourceLocation resourceLoc = resourceKey.location();
                String resourcePath = resourceLoc.getPath();

                // Check if this is a per-world pattern (great spell)
                boolean isPerWorldPattern = false;
                try {
                    isPerWorldPattern = registry.getHolderOrThrow(resourceKey).is(HexTags.Actions.PER_WORLD_PATTERN);
                } catch (Exception e) {
                    // Fallback if holder check fails
                    isPerWorldPattern = false;
                }

                // Build the translation key
                String translationKey = "hexcasting.action." + resourceLoc.toString();
                String translatedName = net.minecraft.client.resources.language.I18n.get(translationKey);

                // Build the display name
                String displayName;
                if (translatedName.equals(translationKey)) {
                    // No translation found, just use the resource path
                    displayName = resourcePath;
                } else {
                    // Format as: Translation Name (resource_path)
                    displayName = translatedName + " (" + resourcePath + ")";
                    matchedPatterns.add(resourcePath); // Track successful matches
                }

                String category = categorizePattern(resourcePath);
                allPatterns.add(new ExtractedPattern(displayName, pattern, category, isPerWorldPattern, resourceLoc.toString()));
            }

            // Now add special patterns that might not be in the registry
            addSpecialPatterns(matchedPatterns);

            // Check for Pehkui interop patterns if the mod is active
            if (PehkuiInterop.isActive()) {
                try {
                    ResourceLocation pehkuiGet = new ResourceLocation("hexcasting", "interop/pehkui/get");
                    ResourceLocation pehkuiSet = new ResourceLocation("hexcasting", "interop/pehkui/set");

                    ResourceKey<ActionRegistryEntry> getKey = ResourceKey.create(registry.key(), pehkuiGet);
                    ResourceKey<ActionRegistryEntry> setKey = ResourceKey.create(registry.key(), pehkuiSet);

                    var getEntry = registry.get(getKey);
                    if (getEntry != null && getEntry.prototype() != null) {
                        allPatterns.add(new ExtractedPattern("Gulliver's Purification (interop/pehkui/get)",
                                getEntry.prototype(), "Interop", false, pehkuiGet.toString()));
                    }

                    var setEntry = registry.get(setKey);
                    if (setEntry != null && setEntry.prototype() != null) {
                        allPatterns.add(new ExtractedPattern("Alter Scale (interop/pehkui/set)",
                                setEntry.prototype(), "Interop", false, pehkuiSet.toString()));
                    }
                } catch (Exception e) {
                    System.out.println("Failed to load Pehkui interop patterns: " + e.getMessage());
                }
            }

            // Sort patterns by category, then by name
            allPatterns.sort((a, b) -> {
                int catCompare = a.category.compareTo(b.category);
                if (catCompare != 0) return catCompare;
                return a.name.compareTo(b.name);
            });

            filteredPatterns = new ArrayList<>(allPatterns);

        } catch (Exception e) {
            System.err.println("Failed to extract patterns from registry: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void addSpecialPatterns(Set<String> matchedPatterns) {
        // Add patterns from SpecialPatterns class with their proper names
        allPatterns.add(new ExtractedPattern("Introspection (open_paren)", SpecialPatterns.INTROSPECTION, "Meta", false, null));
        allPatterns.add(new ExtractedPattern("Retrospection (close_paren)", SpecialPatterns.RETROSPECTION, "Meta", false, null));
        allPatterns.add(new ExtractedPattern("Consideration (escape)", SpecialPatterns.CONSIDERATION, "Meta", false, null));
        allPatterns.add(new ExtractedPattern("Evanition (undo)", SpecialPatterns.EVANITION, "Meta", false, null));
    }

    private void addPatternIfNotMatched(String name, HexPattern pattern, String category, Set<String> matchedPatterns) {
        if (!matchedPatterns.contains(name)) {
            // Pattern not found in registry - mark as unmatched (will be red)
            String displayName = "§c" + name + " (unmatched)§r"; // Using Minecraft color codes
            // Pass null for resourceLocation since these are unmatched patterns
            allPatterns.add(new ExtractedPattern(displayName, pattern, category, false, null));
        }
    }

    private String categorizePattern(String name) {
        name = name.toLowerCase();
        if (name.contains("const")) return "Constants";
        if (name.contains("get") || name.contains("entity")) return "Selectors";
        if (name.contains("raycast")) return "Raycast";
        if (name.contains("zone")) return "Zones";
        if (name.contains("add") || name.contains("sub") || name.contains("mul") || name.contains("div")) return "Math";
        if (name.contains("and") || name.contains("or") || name.contains("not") || name.contains("if")) return "Logic";
        if (name.contains("list") || name.contains("index") || name.contains("append")) return "Lists";
        if (name.contains("eval") || name.contains("read") || name.contains("write")) return "Meta";
        if (name.contains("craft")) return "Crafting";
        if (name.contains("potion")) return "Potions";
        if (name.contains("sentinel")) return "Sentinel";
        if (name.contains("akashic")) return "Akashic";
        if (name.contains("swap") || name.contains("dup") || name.contains("rotate")) return "Stack";
        return "Spells";
    }

    @Override
    protected void init() {
        super.init();

        try {
            Method hidePatterns = parentGui.getClass().getMethod("hexcastingplus$setHidePatternsForLibrary", boolean.class);
            hidePatterns.invoke(parentGui, true);
        } catch (Exception e) {
            // Ignore if method not found
        }

        // Force reload of BruteforceManager cache to pick up any newly learned scrolls
        BruteforceManager.getInstance().reloadCache();
        BruteforceManager.getInstance().ensureCacheLoaded();

        // Clear and reload pattern registry to ensure it's fresh
        try {
            // Force PatternResolver to reinitialize
            java.lang.reflect.Field registryField = com.t.hexcastingplus.common.pattern.PatternResolver.class
                    .getDeclaredField("patternRegistry");
            registryField.setAccessible(true);
            registryField.set(null, null);

            java.lang.reflect.Field cacheField = com.t.hexcastingplus.common.pattern.PatternResolver.class
                    .getDeclaredField("angleSignatureCache");
            cacheField.setAccessible(true);
            cacheField.set(null, null);

            com.t.hexcastingplus.common.pattern.PatternResolver.initializeRegistry();
        } catch (Exception e) {
            // Fallback to just initializing
            com.t.hexcastingplus.common.pattern.PatternResolver.initializeRegistry();
        }

        // Re-extract patterns with fresh cache
        allPatterns.clear();
        filteredPatterns.clear();
        extractPatternsFromHexActions();

        HexCastingPlusClientConfig.load();

        int centerX = this.width / 2;

        // Create pattern list FIRST - before search box setup
        this.patternList = new PatternListArea(centerX - 150, 50, 300, this.height - 100);

        // Search box
        this.searchBox = new EditBox(this.font, centerX - 100, 20, 200, 20, Component.literal("Search"));
        this.searchBox.setHint(Component.literal("Search patterns..."));
        this.searchBox.setResponder(this::updateSearch);
        this.addWidget(searchBox);

        // Restore last search term AFTER pattern list exists
        if (!lastSearchTerm.isEmpty()) {
            searchBox.setValue(lastSearchTerm);
        }

        this.setInitialFocus(this.searchBox);

        // Restore scroll position after list is created
        if (lastScrollPosition > 0) {
            patternList.scrollOffset = lastScrollPosition;
        }

        // Restore selection if valid
        if (lastSelectedIndex >= 0 && lastSelectedIndex < filteredPatterns.size()) {
            selectedIndex = lastSelectedIndex;
        }

        // Buttons - all on bottom row
        int buttonY = this.height - 35;
        int buttonWidth = 70;
        int buttonSpacing = 8;
        int totalWidth = buttonWidth * 3 + buttonSpacing * 2;
        int startX = centerX - totalWidth / 2;

        // Mode toggle button
        this.toggleMethodButton = new SmallButton(
                startX,
                buttonY,
                buttonWidth,
                20,
                Component.literal("Mode: " + (HexCastingPlusClientConfig.useDrawingMethod() ? "Draw" : "Packet")),
                button -> {
                    boolean newValue = !HexCastingPlusClientConfig.useDrawingMethod();
                    HexCastingPlusClientConfig.setUseDrawingMethod(newValue);
                    button.setMessage(Component.literal("Mode: " + (newValue ? "Draw" : "Packet")));
                }
        );

        // Add button
        this.addButton = new SmallButton(
                startX + buttonWidth + buttonSpacing,
                buttonY,
                buttonWidth,
                20,
                Component.literal("Add"),
                button -> addSelectedPattern()
        );

        // Cancel/Close button
        this.closeButton = new SmallButton(
                startX + (buttonWidth + buttonSpacing) * 2,
                buttonY,
                buttonWidth,
                20,
                Component.literal("Cancel"),
                button -> this.onClose()
        );

        this.addRenderableWidget(toggleMethodButton);
        this.addRenderableWidget(addButton);
        this.addRenderableWidget(closeButton);

        updateButtonStates();
    }

    private static class SmallButton extends Button {
        private static final float TEXT_SCALE = 0.8f;

        public SmallButton(int x, int y, int width, int height, Component text, OnPress onPress) {
            super(x, y, width, height, text, onPress, DEFAULT_NARRATION);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            // Dark button background
            int bgColor = this.isHovered ? 0xFF2A2A2A : 0xFF1A1A1A;
            if (!this.active) bgColor = 0xFF0A0A0A;

            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            // Border
            int borderColor = this.active ? (this.isHovered ? 0xFF606060 : 0xFF404040) : 0xFF202020;
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, borderColor);
            guiGraphics.fill(this.getX(), this.getY() + this.height - 1, this.getX() + this.width, this.getY() + this.height, borderColor);
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.height, borderColor);
            guiGraphics.fill(this.getX() + this.width - 1, this.getY(), this.getX() + this.width, this.getY() + this.height, borderColor);

            // Text - smaller font without drop shadow
            int textColor = this.active ? 0xFFFFFF : 0x808080;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(this.getX() + this.width / 2.0f,
                    this.getY() + this.height / 2.0f, 0);
            guiGraphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0f);

            // Calculate text width for manual centering
            int textWidth = Minecraft.getInstance().font.width(this.getMessage());

            // Draw without shadow (false parameter)
            guiGraphics.drawString(Minecraft.getInstance().font, this.getMessage(),
                    -textWidth / 2, -Minecraft.getInstance().font.lineHeight / 2,
                    textColor, false);
            guiGraphics.pose().popPose();
        }
    }

    private void updateSearch(String searchTerm) {
        // Trim leading/trailing spaces for the actual search
        String trimmedSearch = searchTerm.trim();

        if (trimmedSearch.isEmpty()) {
            filteredPatterns = new ArrayList<>(allPatterns);
            patternList.setCategoriesWithMatches(new HashSet<>());
        } else {
            String lower = trimmedSearch.toLowerCase();  // Use trimmed search

            // Find patterns that match
            List<ExtractedPattern> matchingPatterns = new ArrayList<>();
            Set<String> categoriesWithMatches = new HashSet<>();

            for (ExtractedPattern p : allPatterns) {
                if (p.name.toLowerCase().contains(lower) || p.category.toLowerCase().contains(lower)) {
                    matchingPatterns.add(p);
                    categoriesWithMatches.add(p.category);
                }
            }

            // Separate into patterns from open vs closed categories
            List<ExtractedPattern> fromOpenCategories = new ArrayList<>();
            List<ExtractedPattern> fromClosedCategories = new ArrayList<>();

            for (ExtractedPattern p : matchingPatterns) {
                if (!CategoryStateManager.isCollapsed(p.category)) {
                    fromOpenCategories.add(p);
                } else {
                    fromClosedCategories.add(p);
                }
            }

            // Sort each group by category then name
            Comparator<ExtractedPattern> comparator = (a, b) -> {
                int catCompare = a.category.compareTo(b.category);
                if (catCompare != 0) return catCompare;
                return a.name.compareTo(b.name);
            };

            fromOpenCategories.sort(comparator);
            fromClosedCategories.sort(comparator);

            // Combine: open categories first, then closed
            filteredPatterns = new ArrayList<>();
            filteredPatterns.addAll(fromOpenCategories);
            filteredPatterns.addAll(fromClosedCategories);

            // Store which categories have matches for rendering
            patternList.setCategoriesWithMatches(categoriesWithMatches);
        }
        selectedIndex = -1;
        patternList.resetScroll();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        String currentSearch = searchBox != null ? searchBox.getValue() : "";
        super.resize(minecraft, width, height);
        if (searchBox != null) {
            searchBox.setValue(currentSearch);
        }
    }


    @Unique
    public String getSearchText() {
        return searchBox != null ? searchBox.getValue() : "";
    }

    private void updateButtonStates() {
        if (selectedIndex >= 0 && selectedIndex < filteredPatterns.size()) {
            ExtractedPattern selected = filteredPatterns.get(selectedIndex);
            addButton.active = true;

            // Change button text based on whether it's an unsolved great spell
            if (selected.isPerWorldPattern && !selected.isSolved) {
                addButton.setMessage(Component.literal("Solve"));
            } else {
                addButton.setMessage(Component.literal("Add"));
            }
        } else {
            addButton.active = false;
            addButton.setMessage(Component.literal("Add"));
        }
    }

    private void addSelectedPattern() {
        if (selectedIndex >= 0 && selectedIndex < filteredPatterns.size()) {
            ExtractedPattern selected = filteredPatterns.get(selectedIndex);

            // Check if this is an unsolved great spell
            if (selected.isPerWorldPattern && !selected.isSolved) {
                // Trigger targeted bruteforce for this specific pattern
                try {
                    Method bruteforceMethod = parentGui.getClass().getMethod("hexcastingplus$bruteforceSpecificPattern",
                            String.class);
                    bruteforceMethod.invoke(parentGui, selected.originalPatternName);
                    this.onClose();
                } catch (Exception e) {
                    System.err.println("Failed to trigger bruteforce: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                // Just use the pattern as-is without trying to normalize
                String angleSignature = selected.angleSignature;
                HexDir startDir = selected.startDir;

                try {
                    Method addMethod = parentGui.getClass().getMethod("hexcastingplus$addHexPattern",
                            String.class, HexDir.class, String.class);
                    addMethod.invoke(parentGui, angleSignature, startDir, selected.name);

                    // Save current state before closing
                    lastScrollPosition = patternList.scrollOffset;
                    lastSearchTerm = searchBox.getValue();
                    lastSelectedIndex = selectedIndex;

                    // close library
                    this.onClose();

                    // re-open library after its closed
                    java.util.concurrent.ScheduledExecutorService executor =
                            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    executor.schedule(() -> {
                        Minecraft.getInstance().execute(() -> {
                            try {
                                Method hideButtons = parentGui.getClass().getMethod("hexcastingplus$setButtonsVisible", boolean.class);
                                hideButtons.invoke(parentGui, false);

                                Method hidePatterns = parentGui.getClass().getMethod("hexcastingplus$setHidePatternsForLibrary", boolean.class);
                                hidePatterns.invoke(parentGui, true);
                            } catch (Exception e) {
                                // Ignore
                            }

                            HexPatternSelectorScreen newScreen = new HexPatternSelectorScreen(parentGui);
                            Minecraft.getInstance().setScreen(newScreen);
                        });
                        executor.shutdown();
                    }, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle search box defocus
        if (searchBox != null && searchBox.isFocused()) {
            if (!searchBox.isMouseOver(mouseX, mouseY)) {
                searchBox.setFocused(false);
            }
        }

        // Add null check here
        if (patternList != null && patternList.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (patternList.isMouseOver(mouseX, mouseY)) {
            patternList.mouseScrolled(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Render the parent GUI (staff) in the background
        if (parentGui != null) {
            parentGui.render(guiGraphics, -1, -1, partialTick); // -1, -1 prevents hover effects
        }

        // Semi-transparent overlay to darken the background
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);

        // Title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 5, 0xFFFFFF);

        // Render search box
        if (searchBox != null) {
            searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // Render pattern list - ADD NULL CHECK
        if (patternList != null) {
            patternList.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // Debug info if no patterns
        if (allPatterns.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "No patterns found", this.width / 2, this.height / 2, 0xFF5555);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        try {
            Method showPatterns = parentGui.getClass().getMethod("hexcastingplus$setHidePatternsForLibrary", boolean.class);
            showPatterns.invoke(parentGui, false);
        } catch (Exception e) {
            // Ignore if method not found
        }

        // Save state when closing normally
        lastScrollPosition = patternList != null ? patternList.scrollOffset : 0;
        lastSearchTerm = searchBox != null ? searchBox.getValue() : "";
        lastSelectedIndex = selectedIndex;

        // Show the buttons again
        try {
            Method showButtons = parentGui.getClass().getMethod("hexcastingplus$setButtonsVisible", boolean.class);
            showButtons.invoke(parentGui, true);
        } catch (Exception e) {
            // Ignore if method not found
        }

        this.minecraft.setScreen(parentGui);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class PatternListArea {
        private final int x, y, width, height;
        private double scrollOffset = 0;
        private final SmoothScrollHelper smoothScroller = new SmoothScrollHelper();
        private static final int ITEM_HEIGHT = 30;
        private static final int CATEGORY_HEIGHT = 20;

        // Unicode arrows
        private static final String ARROW_RIGHT = "▶";
        private static final String ARROW_DOWN = "▼";

        private Set<String> categoriesWithMatches = new HashSet<>();

        void setCategoriesWithMatches(Set<String> categories) {
            this.categoriesWithMatches = categories;
        }

        void resetScroll() {
            scrollOffset = 0;
            smoothScroller.stopScroll();
        }

        PatternListArea(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            CategoryStateManager.load();
        }

        void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            scrollOffset = smoothScroller.updateSmoothScroll(scrollOffset, getMaxScroll());
            // Background
            guiGraphics.fill(x, y, x + width, y + height, 0x80000000);

            // Enable scissor for scrolling
            guiGraphics.enableScissor(x, y, x + width, y + height);

            String lastCategory = "";
            int yPos = y - (int)scrollOffset;
            boolean isSearching = !getSearchText().isEmpty();
            boolean dividerDrawn = false;

            for (int i = 0; i < filteredPatterns.size(); i++) {
                ExtractedPattern pattern = filteredPatterns.get(i);

                // Draw divider between matching and non-matching categories during search
                if (isSearching && !dividerDrawn && !categoriesWithMatches.isEmpty() &&
                        !categoriesWithMatches.contains(pattern.category)) {
                    // Draw divider line
                    if (yPos >= y && yPos < y + height) {
                        guiGraphics.fill(x + 10, yPos + 2, x + width - 10, yPos + 3, 0xFF666666);
                    }
                    yPos += 8; // Add some spacing
                    dividerDrawn = true;
                }

                // Draw category header if changed
                if (!pattern.category.equals(lastCategory)) {
                    if (yPos >= y - CATEGORY_HEIGHT && yPos < y + height) {
                        // Check if mouse is over category header
                        boolean categoryHovered = mouseX >= x && mouseX < x + width &&
                                mouseY >= yPos && mouseY < yPos + CATEGORY_HEIGHT;

                        // Draw category background
                        if (categoryHovered) {
                            guiGraphics.fill(x, yPos, x + width, yPos + CATEGORY_HEIGHT, 0x40FFFFFF);
                        }

                        // Determine if collapsed based on search state
                        boolean isCollapsed;
                        if (isSearching) {
                            isCollapsed = false;
                        } else {
                            isCollapsed = CategoryStateManager.isCollapsed(pattern.category);
                        }

                        String arrow = isCollapsed ? ARROW_RIGHT : ARROW_DOWN;
                        String categoryText = arrow + " " + pattern.category;

                        // Different color for categories with matches during search - changed to light gray
                        int categoryColor = 0xFFFFAA00;
                        if (isSearching && categoriesWithMatches.contains(pattern.category)) {
                            categoryColor = 0xFFCCCCCC;  // Light gray for matching categories
                        }

                        guiGraphics.drawString(font, categoryText, x + 5, yPos + 5, categoryColor);
                    }
                    yPos += CATEGORY_HEIGHT;
                    lastCategory = pattern.category;

                    // Skip patterns if category is collapsed (but not during search)
                    boolean shouldSkip = isSearching ? false : CategoryStateManager.isCollapsed(pattern.category);

                    if (shouldSkip) {
                        int j = i;
                        while (j < filteredPatterns.size() &&
                                filteredPatterns.get(j).category.equals(pattern.category)) {
                            j++;
                        }
                        i = j - 1;
                        continue;
                    }
                }

                // Draw pattern entry (only if category is not collapsed)
                boolean isCollapsed = isSearching ? false : CategoryStateManager.isCollapsed(pattern.category);
                if (!isCollapsed) {
                    if (yPos >= y - ITEM_HEIGHT && yPos < y + height) {
                        boolean isSelected = i == selectedIndex;
                        boolean isHovered = mouseX >= x && mouseX < x + width &&
                                mouseY >= yPos && mouseY < yPos + ITEM_HEIGHT;

                        if (isSelected) {
                            guiGraphics.fill(x, yPos, x + width, yPos + ITEM_HEIGHT, 0x80FFFFFF);
                        } else if (isHovered) {
                            guiGraphics.fill(x, yPos, x + width, yPos + ITEM_HEIGHT, 0x40FFFFFF);
                        }

                        // Pattern name - check if it's a great spell (per-world pattern)
                        int textColor = 0xFFFFFF; // default white
                        if (pattern.isPerWorldPattern && !pattern.isSolved) {
                            textColor = 0xFF5555; // Red for unsolved great spells
                        }
                        guiGraphics.drawString(font, pattern.name, x + 20, yPos + 5, textColor);

                        // Mini pattern preview
                        int previewX = x + width - 30;
                        int previewY = yPos + 5;
                        int previewSize = 20;

                        PatternRenderer.renderMiniPattern(guiGraphics, pattern.pattern,
                                previewX, previewY, previewSize);
                    }
                    yPos += ITEM_HEIGHT;
                }
            }

            guiGraphics.disableScissor();

            // Scrollbar
            if (getMaxScroll() > 0) {
                int scrollbarHeight = Math.max(20, (int)((float)height * height / getTotalHeight()));
                int scrollbarY = y + (int)((height - scrollbarHeight) * scrollOffset / getMaxScroll());
                guiGraphics.fill(x + width - 5, scrollbarY, x + width, scrollbarY + scrollbarHeight, 0xFFAAAAAA);
            }
        }

        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isMouseOver(mouseX, mouseY)) return false;

            int yPos = y - (int)scrollOffset;
            String lastCategory = "";

            for (int i = 0; i < filteredPatterns.size(); i++) {
                ExtractedPattern pattern = filteredPatterns.get(i);

                if (!pattern.category.equals(lastCategory)) {
                    // Check if click is on category header
                    if (mouseY >= yPos && mouseY < yPos + CATEGORY_HEIGHT) {
                        boolean isSearching = !getSearchText().isEmpty();
                        if (!isSearching) {
                            CategoryStateManager.toggleCategory(pattern.category);
                        }
                        return true;
                    }
                    yPos += CATEGORY_HEIGHT;
                    lastCategory = pattern.category;

                    // Skip if collapsed (but not during search)
                    boolean isSearching = !getSearchText().isEmpty();
                    boolean shouldSkip = isSearching ? false : CategoryStateManager.isCollapsed(pattern.category);
                    if (shouldSkip) {
                        int j = i;
                        while (j < filteredPatterns.size() &&
                                filteredPatterns.get(j).category.equals(pattern.category)) {
                            j++;
                        }
                        i = j - 1;
                        continue;
                    }
                }

                if (!CategoryStateManager.isCollapsed(pattern.category)) {
                    if (mouseY >= yPos && mouseY < yPos + ITEM_HEIGHT) {
                        selectedIndex = i;
                        updateButtonStates();
                        return true;
                    }
                    yPos += ITEM_HEIGHT;
                }
            }

            return false;
        }

        int getTotalHeight() {
            int totalHeight = 0;
            String lastCategory = "";
            boolean isSearching = !getSearchText().isEmpty();
            boolean dividerAdded = false;

            for (ExtractedPattern pattern : filteredPatterns) {
                // Add divider height if needed
                if (isSearching && !dividerAdded && !categoriesWithMatches.isEmpty() &&
                        !categoriesWithMatches.contains(pattern.category)) {
                    totalHeight += 8; // Divider spacing
                    dividerAdded = true;
                }

                if (!pattern.category.equals(lastCategory)) {
                    totalHeight += CATEGORY_HEIGHT;
                    lastCategory = pattern.category;

                    if (CategoryStateManager.isCollapsed(pattern.category) && !isSearching) {
                        continue;
                    }
                }

                if (!CategoryStateManager.isCollapsed(pattern.category) || isSearching) {
                    totalHeight += ITEM_HEIGHT;
                }
            }
            return totalHeight;
        }

        void mouseScrolled(double delta) {
            smoothScroller.addScrollVelocity(delta, ITEM_HEIGHT);
        }

        boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        double getMaxScroll() {
            return Math.max(0, getTotalHeight() - height);
        }
    }
}