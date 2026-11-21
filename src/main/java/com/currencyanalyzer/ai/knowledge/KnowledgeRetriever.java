package com.currencyanalyzer.ai.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KnowledgeRetriever {

    static final int MINIMUM_SCORE = 8;

    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z][a-z0-9]{2,}");
    private static final Set<String> STOP_PHRASES = Set.of(
        "什么", "怎么", "如何", "为什么", "是否", "可以", "这个", "那个", "一个", "我们", "你们", "他们",
        "请用", "解释", "说明", "一下", "以及", "还有", "不是", "没有", "因为", "所以"
    );

    private final KnowledgeLibrary library;

    public KnowledgeRetriever(KnowledgeLibrary library) {
        this.library = library;
    }

    public List<KnowledgeNote> retrieve(String query, int maxNotes) {
        if (query == null || query.isBlank() || maxNotes < 1) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> phrases = cjkPhrases(normalized);
        List<String> tokens = latinTokens(normalized);
        List<ScoredNote> scored = new ArrayList<>();
        for (KnowledgeNote note : library.notes()) {
            int score = score(note, normalized, phrases, tokens);
            if (score >= MINIMUM_SCORE) {
                scored.add(new ScoredNote(note, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredNote::score).reversed()
            .thenComparing(scoredNote -> scoredNote.note().title()));
        if (scored.size() > maxNotes) {
            scored = scored.subList(0, maxNotes);
        }
        return scored.stream().map(ScoredNote::note).toList();
    }

    private static int score(KnowledgeNote note, String query, List<String> phrases, List<String> tokens) {
        String title = note.title().toLowerCase(Locale.ROOT);
        String body = note.body().toLowerCase(Locale.ROOT);
        int score = 0;
        if (!title.isBlank() && query.contains(title)) {
            score += 30;
        }
        int bodyScore = 0;
        for (String phrase : phrases) {
            if (title.contains(phrase)) {
                score += phrase.length() >= 4 ? 16 : (phrase.length() == 3 ? 10 : 6);
            } else if (body.contains(phrase)) {
                bodyScore += phrase.length() >= 4 ? 4 : 1;
            }
        }
        for (String token : tokens) {
            if (title.contains(token)) {
                score += 10;
            } else if (body.contains(token)) {
                bodyScore += token.length() >= 5 ? 4 : 2;
            }
        }
        return score + Math.min(bodyScore, 12);
    }

    static List<String> cjkPhrases(String text) {
        Set<String> phrases = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char current = i < text.length() ? text.charAt(i) : '\0';
            if (i < text.length() && Character.UnicodeScript.of(current) == Character.UnicodeScript.HAN) {
                run.append(current);
                continue;
            }
            if (run.length() >= 2) {
                String value = run.toString();
                int longest = Math.min(value.length(), 12);
                for (int size = 2; size <= longest; size++) {
                    for (int start = 0; start + size <= value.length(); start++) {
                        String phrase = value.substring(start, start + size);
                        if (!STOP_PHRASES.contains(phrase)) {
                            phrases.add(phrase);
                        }
                    }
                }
            }
            run.setLength(0);
        }
        return List.copyOf(phrases);
    }

    private static List<String> latinTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = LATIN_TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return List.copyOf(tokens);
    }

    private record ScoredNote(KnowledgeNote note, int score) {
    }
}
