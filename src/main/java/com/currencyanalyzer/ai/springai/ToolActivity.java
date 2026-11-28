package com.currencyanalyzer.ai.springai;

/**
 * Carries the current chat's tool listener onto the thread that runs a tool.
 * Spring AI invokes the tool on that same thread.
 */
public final class ToolActivity {

    public interface Listener {
        void started(String name);

        default void started(String name, String argumentsJson) {
            started(name);
        }

        void finished(String name, String argumentsJson, String result, int durationMs);
    }

    private static final ThreadLocal<Listener> CURRENT = new ThreadLocal<>();

    private ToolActivity() {
    }

    public static void bind(Listener listener) {
        CURRENT.set(listener);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void started(String name) {
        started(name, "{}");
    }

    public static void started(String name, String argumentsJson) {
        Listener listener = CURRENT.get();
        if (listener != null) {
            listener.started(name, argumentsJson);
        }
    }

    public static void finished(String name, String argumentsJson, String result, int durationMs) {
        Listener listener = CURRENT.get();
        if (listener != null) {
            listener.finished(name, argumentsJson, result, durationMs);
        }
    }
}
