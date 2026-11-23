package com.currencyanalyzer.ai.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a short follow-up into a standalone retrieval query by keeping recent turns with it.
 * A self-contained question is searched as written.
 */
public final class RetrievalQueryRewriter {

    static final int MAX_QUERY_CHARS = 800;
    private static final int MAX_PRIOR_TURNS = 4;
    private static final int MAX_TURN_CHARS = 180;
    private static final int SHORT_FOLLOW_UP_CHARS = 60;

    private static final Pattern ABOUT = Pattern.compile("(?i)^(what|how) about\\b");
    private static final Pattern ANAPHORA = Pattern.compile(
        "(?iu)\\b(he|she|his|her|their|they|him|them|that|this|those|these|it)\\b|[他她它其]"
    );

    private RetrievalQueryRewriter() {
    }

    public record SpokenTurn(String role, String content) {
    }

    public static String rewrite(List<SpokenTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        int latestUser = -1;
        for (int index = history.size() - 1; index >= 0; index--) {
            if (isUser(history.get(index))) {
                latestUser = index;
                break;
            }
        }
        if (latestUser < 0) {
            return "";
        }
        String question = singleLine(history.get(latestUser).content(), MAX_QUERY_CHARS);
        if (question.isBlank() || !isFollowUp(question)) {
            return question;
        }
        List<String> prior = new ArrayList<>();
        for (int index = Math.max(0, latestUser - MAX_PRIOR_TURNS); index < latestUser; index++) {
            SpokenTurn turn = history.get(index);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (!isUser(turn) && !"assistant".equals(role(turn))) {
                continue;
            }
            prior.add(singleLine(turn.content(), MAX_TURN_CHARS));
        }
        if (prior.isEmpty()) {
            return question;
        }
        return cap(String.join(". ", prior), question);
    }

    static boolean isFollowUp(String text) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (ABOUT.matcher(stripped).find()) {
            return true;
        }
        return stripped.length() <= SHORT_FOLLOW_UP_CHARS && ANAPHORA.matcher(stripped).find();
    }

    private static String cap(String prior, String question) {
        String suffix = "\n" + question;
        if (suffix.length() >= MAX_QUERY_CHARS) {
            return question.substring(0, MAX_QUERY_CHARS);
        }
        int room = MAX_QUERY_CHARS - suffix.length();
        String head = prior.length() <= room ? prior : prior.substring(prior.length() - room);
        return (head + suffix).strip();
    }

    private static String singleLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("\\s+", " ").strip();
        if (line.length() <= maxChars) {
            return line;
        }
        return line.substring(0, maxChars);
    }

    private static boolean isUser(SpokenTurn turn) {
        return "user".equals(role(turn));
    }

    private static String role(SpokenTurn turn) {
        return turn.role() == null ? "" : turn.role();
    }
}
