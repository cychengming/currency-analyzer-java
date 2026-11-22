package com.currencyanalyzer.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Component
public class KnowledgeLibrary {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLibrary.class);
    private static final List<String> LOCATIONS = List.of(
        "classpath*:knowledge/guides/*.md"
    );

    private final List<KnowledgeNote> notes;

    @Autowired
    public KnowledgeLibrary() {
        this(loadClasspathNotes());
    }

    public KnowledgeLibrary(List<KnowledgeNote> notes) {
        this.notes = List.copyOf(notes);
    }

    public List<KnowledgeNote> notes() {
        return notes;
    }

    private static List<KnowledgeNote> loadClasspathNotes() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<KnowledgeNote> loaded = new ArrayList<>();
        for (String location : LOCATIONS) {
            try {
                Resource[] resources = resolver.getResources(location);
                Arrays.sort(resources, Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
                for (Resource resource : resources) {
                    loaded.add(read(resource, "guides"));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read assistant knowledge from " + location, exception);
            }
        }
        log.info("Loaded {} built-in assistant guide(s)", loaded.size());
        return List.copyOf(loaded);
    }

    private static KnowledgeNote read(Resource resource, String folder) throws IOException {
        String filename = resource.getFilename() == null ? "note.md" : resource.getFilename();
        String path = "knowledge/" + folder + "/" + filename;
        String raw;
        try (InputStream input = resource.getInputStream()) {
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String body = stripFrontMatter(raw).strip();
        String title = heading(body);
        if (title.isBlank()) {
            title = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
        }
        return new KnowledgeNote(path, title, body);
    }

    static String stripFrontMatter(String raw) {
        if (!raw.startsWith("---")) {
            return raw;
        }
        int end = raw.indexOf("\n---", 3);
        if (end < 0) {
            return raw;
        }
        int bodyStart = raw.indexOf('\n', end + 4);
        return bodyStart < 0 ? "" : raw.substring(bodyStart + 1);
    }

    private static String heading(String body) {
        for (String line : body.split("\n", 40)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).strip();
            }
        }
        return "";
    }
}
