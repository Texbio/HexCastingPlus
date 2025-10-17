package com.t.hexcastingplus.common.pattern;

import java.util.*;

/**
 * Centralized error handler for pattern loading issues.
 * Prevents duplicate error messages and provides cleaner output.
 */
public class PatternErrorHandler {
    // Track errors per screen/session to avoid duplicates
    private static final Map<String, Set<String>> fileErrors = new HashMap<>();
    private static String currentContext = "default";

    /**
     * Start a new error tracking context (e.g., when opening a new screen)
     */
    public static void startNewContext(String context) {
        currentContext = context;
        fileErrors.computeIfAbsent(context, k -> new HashSet<>());
    }

    /**
     * Clear errors for the current context
     */
    public static void clearCurrentContext() {
        fileErrors.remove(currentContext);
    }

    /**
     * Report an error only if it hasn't been reported yet in this context
     */
    public static void reportError(String filename, String error) {
        Set<String> contextErrors = fileErrors.computeIfAbsent(currentContext, k -> new HashSet<>());
        String errorKey = filename + ":" + error;

        if (!contextErrors.contains(errorKey)) {
            contextErrors.add(errorKey);

            // Format the error message nicely
            if (filename != null && !filename.isEmpty()) {
                System.out.println("[HexCasting+] ERROR in " + filename + ": " + error);
            } else {
                System.out.println("[HexCasting+] ERROR: " + error);
            }
        }
    }

    /**
     * Check if an error has already been reported
     */
    public static boolean hasReportedError(String filename, String error) {
        Set<String> contextErrors = fileErrors.get(currentContext);
        if (contextErrors == null) return false;

        String errorKey = filename + ":" + error;
        return contextErrors.contains(errorKey);
    }

    /**
     * Report a great spell error
     */
    public static void reportGreatSpellError(String filename, String spellName) {
        String error = "Great spell '" + spellName + "' not resolved";
        reportError(filename, error);
    }

    /**
     * Report a parse error
     */
    public static void reportParseError(String filename, String line) {
        // Only report parse errors for non-trivial lines
        if (line != null && !line.trim().isEmpty() && !line.startsWith("//")) {
            String error = "Failed to parse: " + (line.length() > 50 ? line.substring(0, 50) + "..." : line);
            reportError(filename, error);
        }
    }
}