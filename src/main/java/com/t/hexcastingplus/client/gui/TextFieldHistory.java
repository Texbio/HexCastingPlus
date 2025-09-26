/**
 * Manages undo/redo history for text fields
 */
package com.t.hexcastingplus.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages undo/redo history for text fields
 */
public class TextFieldHistory {
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();
    private String currentText = "";
    private String lastKnownText = "";
    private long lastChangeTime = 0;
    private static final long CHANGE_THRESHOLD_MS = 500; // Group changes within 500ms
    private static final int MAX_HISTORY_SIZE = 30;

    public TextFieldHistory(String initialText) {
        this.currentText = initialText;
        this.lastKnownText = initialText;
    }

    /**
     * Check if text has changed and record it if necessary
     * Call this in tick() method
     */
    public void checkAndRecord(String newText) {
        if (!newText.equals(lastKnownText)) {
            long currentTime = System.currentTimeMillis();

            // If enough time has passed since last change, save the previous state
            if (currentTime - lastChangeTime > CHANGE_THRESHOLD_MS && !lastKnownText.equals(currentText)) {
                pushToUndoStack(currentText);
            }

            currentText = newText;
            lastKnownText = newText;
            lastChangeTime = currentTime;

            // Clear redo stack on new changes
            redoStack.clear();
        }
    }

    /**
     * Force save current state (useful before major operations)
     */
    public void saveState(String text) {
        if (!text.equals(currentText)) {
            pushToUndoStack(currentText);
            currentText = text;
            lastKnownText = text;
            redoStack.clear();
        }
    }

    /**
     * Perform undo operation
     * @return The text to restore, or null if nothing to undo
     */
    public String undo() {
        // First, save current state if it's different from what we last saved
        if (!currentText.equals(lastKnownText)) {
            pushToUndoStack(currentText);
        }

        if (!undoStack.isEmpty()) {
            String previousText = undoStack.remove(undoStack.size() - 1);
            redoStack.add(currentText);
            currentText = previousText;
            lastKnownText = previousText;
            return previousText;
        }
        return null;
    }

    /**
     * Perform redo operation
     * @return The text to restore, or null if nothing to redo
     */
    public String redo() {
        if (!redoStack.isEmpty()) {
            String redoText = redoStack.remove(redoStack.size() - 1);
            pushToUndoStack(currentText);
            currentText = redoText;
            lastKnownText = redoText;
            return redoText;
        }
        return null;
    }

    /**
     * Clear the text (for right-click clear)
     */
    public void clear() {
        if (!currentText.isEmpty()) {
            pushToUndoStack(currentText);
            currentText = "";
            lastKnownText = "";
            redoStack.clear();
        }
    }

    private void pushToUndoStack(String text) {
        // Don't push empty strings or duplicates
        if (!text.isEmpty() && (undoStack.isEmpty() || !undoStack.get(undoStack.size() - 1).equals(text))) {
            undoStack.add(text);

            // Limit stack size
            if (undoStack.size() > MAX_HISTORY_SIZE) {
                undoStack.remove(0);
            }
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Clean up resources - call this when the dialog/screen closes
     */
    public void cleanup() {
        undoStack.clear();
        redoStack.clear();
        currentText = "";
        lastKnownText = "";
    }
}