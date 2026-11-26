package com.currencyanalyzer.ai.tools;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Carries the authenticated conversation owner's username and per-turn attached RAG note IDs
 * onto the thread executing AI tools so personal tools stay user-scoped and {@code search_notes}
 * avoids returning duplicate reference chunks already attached in the prompt.
 */
public final class ToolUserContext {

    private static final ThreadLocal<String> CURRENT_USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<Set<String>> ATTACHED_NOTE_IDS = new ThreadLocal<>();

    private ToolUserContext() {
    }

    public static void bind(String username) {
        if (username == null || username.isBlank()) {
            CURRENT_USERNAME.remove();
        } else {
            CURRENT_USERNAME.set(username.trim());
        }
    }

    public static String currentUsername() {
        return CURRENT_USERNAME.get();
    }

    public static void recordAttachedNoteIds(Collection<String> noteIds) {
        if (noteIds == null || noteIds.isEmpty()) {
            return;
        }
        Set<String> current = ATTACHED_NOTE_IDS.get();
        if (current == null) {
            current = new LinkedHashSet<>();
            ATTACHED_NOTE_IDS.set(current);
        }
        for (String noteId : noteIds) {
            if (noteId != null && !noteId.isBlank()) {
                current.add(noteId.trim());
            }
        }
    }

    public static Set<String> attachedNoteIds() {
        Set<String> current = ATTACHED_NOTE_IDS.get();
        return current == null || current.isEmpty() ? Set.of() : Set.copyOf(current);
    }

    public static void clear() {
        CURRENT_USERNAME.remove();
        ATTACHED_NOTE_IDS.remove();
    }
}
