package com.currencyanalyzer.ai.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class TextDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".md", ".markdown", ".txt", ".csv");
    private static final Set<String> LOW_SIGNAL_HEADINGS = Set.of("标签", "tags", "tag");
    private static final int MIN_STANDALONE_SECTION_CHARS = 45;
    private static final int MAX_QUOTE_METADATA_STUB_CHARS = 140;

    private final int chunkMaxChars;
    private final int chunkOverlapChars;
    private final int maxChunksPerDocument;

    public TextDocumentParser(
        @Value("${app.rag.chunk-max-chars:600}") int chunkMaxChars,
        @Value("${app.rag.chunk-overlap-chars:100}") int chunkOverlapChars,
        @Value("${app.rag.max-chunks-per-document:500}") int maxChunksPerDocument
    ) {
        this.chunkMaxChars = Math.max(120, chunkMaxChars);
        this.chunkOverlapChars = Math.max(0, Math.min(chunkOverlapChars, this.chunkMaxChars / 2));
        this.maxChunksPerDocument = Math.max(1, maxChunksPerDocument);
    }

    @Override
    public boolean supports(String filename) {
        String ext = extension(filename);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    @Override
    public String inferContentType(String filename) {
        return switch (extension(filename)) {
            case ".md", ".markdown" -> "text/markdown; charset=utf-8";
            case ".csv" -> "text/csv; charset=utf-8";
            default -> "text/plain; charset=utf-8";
        };
    }

    @Override
    public ParsedDocument parse(String filename, byte[] rawBytes, String titleOverride) {
        if (!supports(filename)) {
            throw new IllegalArgumentException("Unsupported file type. Supported formats: .md, .txt, .csv");
        }
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
        String text = decodeUtf8(rawBytes);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Uploaded file contains no readable text");
        }

        String ext = extension(filename);
        if (".csv".equals(ext)) {
            String title = resolveTitle(titleOverride, "", filename);
            return new ParsedDocument(title, parseCsv(text));
        }
        if (".md".equals(ext) || ".markdown".equals(ext)) {
            return parseMarkdown(filename, text, titleOverride);
        }
        String title = resolveTitle(titleOverride, "", filename);
        return new ParsedDocument(title, chunkPlainText(text, null));
    }

    private ParsedDocument parseMarkdown(String filename, String raw, String titleOverride) {
        String body = stripFrontMatter(raw).replace("\r\n", "\n").replace('\r', '\n').strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Uploaded Markdown file contains no readable text");
        }
        String detectedTitle = firstMarkdownHeading(body);
        List<Section> sections = mergeSmallSections(splitMarkdownSections(body));
        if (detectedTitle.isBlank()) {
            for (Section section : sections) {
                if (section.heading != null && !section.heading.isBlank()) {
                    detectedTitle = section.heading;
                    break;
                }
            }
        }
        String title = resolveTitle(titleOverride, detectedTitle, filename);

        List<ParsedChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            if (chunks.size() >= maxChunksPerDocument) {
                break;
            }
            String sectionBody = section.body.strip();
            if (sectionBody.isEmpty()) {
                continue;
            }
            for (String piece : splitWithOverlap(sectionBody)) {
                if (chunks.size() >= maxChunksPerDocument) {
                    break;
                }
                chunks.add(new ParsedChunk(chunks.size(), section.heading, piece));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(new ParsedChunk(0, detectedTitle.isBlank() ? null : detectedTitle, body));
        }
        return new ParsedDocument(title, List.copyOf(chunks));
    }

    private List<ParsedChunk> chunkPlainText(String raw, String heading) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        List<String> pieces = splitWithOverlap(normalized);
        List<ParsedChunk> chunks = new ArrayList<>();
        for (String piece : pieces) {
            if (chunks.size() >= maxChunksPerDocument) {
                break;
            }
            chunks.add(new ParsedChunk(chunks.size(), heading, piece));
        }
        return List.copyOf(chunks);
    }

    private List<ParsedChunk> parseCsv(String raw) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        String header = null;
        List<String> rows = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (header == null) {
                header = trimmed;
            } else {
                rows.add(trimmed);
            }
        }
        if (header == null) {
            throw new IllegalArgumentException("Uploaded CSV file is empty");
        }
        if (rows.isEmpty()) {
            return List.of(new ParsedChunk(0, "CSV Header", header));
        }

        List<ParsedChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int startRow = 1;
        int currentRow = 0;
        for (String row : rows) {
            currentRow++;
            if (current.length() > header.length() && current.length() + 1 + row.length() > chunkMaxChars) {
                int endRow = currentRow - 1;
                chunks.add(new ParsedChunk(
                    chunks.size(),
                    "Rows " + startRow + "-" + endRow,
                    current.toString()
                ));
                if (chunks.size() >= maxChunksPerDocument) {
                    return List.copyOf(chunks);
                }
                current = new StringBuilder(header);
                startRow = currentRow;
            }
            current.append('\n').append(row);
        }
        if (current.length() > header.length() && chunks.size() < maxChunksPerDocument) {
            chunks.add(new ParsedChunk(
                chunks.size(),
                "Rows " + startRow + "-" + currentRow,
                current.toString()
            ));
        }
        return List.copyOf(chunks);
    }

    private static String firstMarkdownHeading(String body) {
        boolean inCodeFence = false;
        for (String line : body.split("\n", 80)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```")) {
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence) {
                continue;
            }
            String heading = extractMarkdownHeading(stripped);
            if (heading != null) {
                return heading;
            }
        }
        return "";
    }

    private static String extractMarkdownHeading(String strippedLine) {
        int hashes = 0;
        int len = strippedLine.length();
        while (hashes < len && hashes < 3 && strippedLine.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes == 0 || hashes >= len || !Character.isWhitespace(strippedLine.charAt(hashes))) {
            return null;
        }
        String text = strippedLine.substring(hashes + 1).strip();
        return text.isEmpty() ? null : text;
    }

    private List<Section> splitMarkdownSections(String body) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = null;
        StringBuilder currentBody = new StringBuilder();
        boolean inCodeFence = false;
        for (String line : body.split("\n", -1)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```")) {
                inCodeFence = !inCodeFence;
            }
            if (!inCodeFence) {
                String heading = extractMarkdownHeading(stripped);
                if (heading != null) {
                    if (!currentBody.isEmpty() && !currentBody.toString().isBlank()) {
                        sections.add(new Section(currentHeading, currentBody.toString()));
                        currentBody.setLength(0);
                    }
                    currentHeading = heading;
                    continue;
                }
            }
            if (!currentBody.isEmpty()) {
                currentBody.append('\n');
            }
            currentBody.append(line);
        }
        if (!currentBody.isEmpty() && !currentBody.toString().isBlank()) {
            sections.add(new Section(currentHeading, currentBody.toString()));
        }
        return sections;
    }

    private List<Section> mergeSmallSections(List<Section> rawSections) {
        if (rawSections.size() <= 1) {
            return rawSections;
        }
        List<Section> filtered = new ArrayList<>();
        for (Section section : rawSections) {
            String normalizedHeading = section.heading == null ? "" : section.heading.strip().toLowerCase(Locale.ROOT);
            if (LOW_SIGNAL_HEADINGS.contains(normalizedHeading) && rawSections.size() > 1) {
                continue;
            }
            filtered.add(section);
        }
        if (filtered.isEmpty()) {
            return rawSections;
        }
        List<Section> merged = new ArrayList<>();
        StringBuilder carryPrefix = new StringBuilder();
        for (int index = 0; index < filtered.size(); index++) {
            Section current = filtered.get(index);
            String trimmed = current.body.strip();
            boolean isTinyMetadataStub = index < filtered.size() - 1
                && (trimmed.length() < MIN_STANDALONE_SECTION_CHARS
                    || (trimmed.startsWith(">") && trimmed.length() <= MAX_QUOTE_METADATA_STUB_CHARS));
            if (isTinyMetadataStub) {
                if (!carryPrefix.isEmpty()) {
                    carryPrefix.append('\n');
                }
                carryPrefix.append(trimmed);
                continue;
            }
            String combined = carryPrefix.isEmpty() ? trimmed : carryPrefix + "\n\n" + trimmed;
            carryPrefix.setLength(0);
            merged.add(new Section(current.heading, combined));
        }
        if (!carryPrefix.isEmpty()) {
            if (merged.isEmpty()) {
                merged.add(new Section(filtered.getFirst().heading, carryPrefix.toString()));
            } else {
                Section last = merged.removeLast();
                merged.add(new Section(last.heading, last.body + "\n\n" + carryPrefix));
            }
        }
        return merged;
    }

    private List<String> splitWithOverlap(String text) {
        String cleaned = text.strip();
        if (cleaned.length() <= chunkMaxChars) {
            return List.of(cleaned);
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        int length = cleaned.length();
        while (start < length) {
            int maxEnd = Math.min(length, start + chunkMaxChars);
            int end = maxEnd;
            if (maxEnd < length) {
                end = bestBreakPoint(cleaned, start, maxEnd);
            }
            String slice = cleaned.substring(start, end).strip();
            if (!slice.isEmpty()) {
                result.add(slice);
            }
            if (end >= length) {
                break;
            }
            int nextStart = Math.max(start + 1, end - chunkOverlapChars);
            if (nextStart < length && cleaned.charAt(nextStart) != '\n') {
                int spaceAfter = cleaned.indexOf(' ', nextStart);
                if (spaceAfter > nextStart && spaceAfter < end) {
                    nextStart = spaceAfter + 1;
                }
            }
            start = nextStart;
        }
        return result;
    }

    private int bestBreakPoint(String text, int start, int maxEnd) {
        int minimumAcceptable = start + (chunkMaxChars / 2);
        int doubleNewline = text.lastIndexOf("\n\n", maxEnd);
        if (doubleNewline >= minimumAcceptable) {
            return doubleNewline;
        }
        int newline = text.lastIndexOf('\n', maxEnd);
        if (newline >= minimumAcceptable) {
            return newline;
        }
        for (int index = maxEnd - 1; index >= minimumAcceptable; index--) {
            char c = text.charAt(index);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return index + 1;
            }
        }
        int space = text.lastIndexOf(' ', maxEnd);
        if (space >= minimumAcceptable) {
            return space;
        }
        return maxEnd;
    }

    private static String decodeUtf8(byte[] rawBytes) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes))
                .toString();
            if (decoded.startsWith("\uFEFF")) {
                decoded = decoded.substring(1);
            }
            if (decoded.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Binary files are not supported");
            }
            return decoded;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("File must be valid UTF-8 text");
        }
    }

    static String stripFrontMatter(String raw) {
        String normalized = raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return raw;
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return raw;
        }
        int bodyStart = normalized.indexOf('\n', end + 4);
        return bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
    }

    private static String resolveTitle(String override, String detectedHeading, String filename) {
        if (override != null && !override.isBlank()) {
            return truncate(override.strip(), 200);
        }
        if (detectedHeading != null && !detectedHeading.isBlank()) {
            return truncate(detectedHeading.strip(), 200);
        }
        String base = baseFilename(filename);
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        return truncate(stem.isBlank() ? "Untitled document" : stem, 200);
    }

    private static String baseFilename(String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String extension(String filename) {
        String base = baseFilename(filename).toLowerCase(Locale.ROOT).trim();
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot) : "";
    }

    private static String truncate(String value, int maxLen) {
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private record Section(String heading, String body) {
    }
}
