package com.t.hexcastingplus.client.gui;

/**
 * Central location for validation patterns and constants used across dialogs
 */
public class ValidationConstants {
    //Debug
    public static final boolean DEBUG = false;
    public static final boolean DEBUG_ERROR = false;

    // Folders: No dots at start/end (Windows convention)
    public static final String FOLDER_NAME_PATTERN = "^(?![.])[^<>:\"|?*\\\\/]+(?<![.])$";

    // Patterns: Allow any position for dots
    public static final String PATTERN_NAME_PATTERN = "^[^<>:\"|?*\\\\/]+$";

    // Maximum lengths
    public static final int MAX_FOLDER_NAME_LENGTH = 25;
    public static final int MAX_PATTERN_NAME_LENGTH = 40;

    // For folders that don't allow dots at start
    public static final String FOLDER_NO_DOT_START_PATTERN = "^[\\.\\s]+$";

    // For checking if name is only spaces
    public static final String ONLY_SPACES_PATTERN = "^\\s+$";

    private ValidationConstants() {
        // Utility class, prevent instantiation
    }
}
