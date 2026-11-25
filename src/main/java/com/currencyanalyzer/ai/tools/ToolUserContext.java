package com.currencyanalyzer.ai.tools;

/**
 * Carries the authenticated conversation owner's username onto the thread executing AI tools
 * so personal tools (such as Alerts 2.0 rules and inbox) are strictly scoped to the signed-in user.
 */
public final class ToolUserContext {

    private static final ThreadLocal<String> CURRENT_USERNAME = new ThreadLocal<>();

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

    public static void clear() {
        CURRENT_USERNAME.remove();
    }
}
