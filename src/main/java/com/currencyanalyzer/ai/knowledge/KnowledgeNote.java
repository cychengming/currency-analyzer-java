package com.currencyanalyzer.ai.knowledge;

public record KnowledgeNote(String path, String title, String body) {

    public String excerpt(int maxChars) {
        String text = body.strip();
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf('\n', maxChars);
        if (cut < maxChars / 2) {
            cut = maxChars;
        }
        return text.substring(0, cut).strip() + "\n…";
    }
}
