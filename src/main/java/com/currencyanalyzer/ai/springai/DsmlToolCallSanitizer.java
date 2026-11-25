package com.currencyanalyzer.ai.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Detects, strips, and recovers leaked DeepSeek Markup Language (DSML) tool call blocks
 * such as {@code <｜DSML｜function_calls>} or malformed {@code <｜｜DSML｜｜ calls>}
 * when DeepSeek fails to convert them into structured {@code tool_calls} JSON.
 */
public final class DsmlToolCallSanitizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> WRAPPER_TAGS = Set.of("calls", "tool_calls", "function_calls");
    private static final char FULLWIDTH_PIPE = '\uFF5C';
    private static final String MARKER = "DSML";

    private DsmlToolCallSanitizer() {
    }

    public static boolean containsDsml(String text) {
        return text != null && findNextTag(text, 0) != null;
    }

    /**
     * Strips any leaked DSML tool-call blocks (complete or truncated) from assistant text.
     */
    public static String stripDsml(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        DsmlTag first = findNextTag(text, 0);
        if (first == null) {
            return text;
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        int cursor = 0;
        DsmlTag tag = first;
        while (tag != null) {
            cleaned.append(text, cursor, tag.start());
            cursor = skipBlockEnd(text, tag);
            tag = findNextTag(text, cursor);
        }
        if (cursor < text.length()) {
            cleaned.append(text, cursor, text.length());
        }
        return normalizeSpacing(cleaned.toString());
    }

    /**
     * Extracts structured tool calls from leaked DSML {@code invoke} blocks.
     * Only tool names present in {@code allowedToolNames} are accepted when the set is non-empty.
     */
    public static List<AssistantMessage.ToolCall> extractToolCalls(String text, Set<String> allowedToolNames) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        int cursor = 0;
        DsmlTag tag = findNextTag(text, cursor);
        while (tag != null) {
            if (!tag.closing() && "invoke".equals(tag.name())) {
                String toolName = parseAttribute(tag.attributes(), "name");
                int bodyEnd = findMatchingCloseStart(text, tag.end(), "invoke");
                String body = text.substring(tag.end(), bodyEnd);
                if (isAllowedTool(toolName, allowedToolNames)) {
                    String argumentsJson = parseParametersJson(body);
                    calls.add(new AssistantMessage.ToolCall(
                        "dsml-call-" + calls.size(),
                        "function",
                        toolName.trim(),
                        argumentsJson
                    ));
                }
                DsmlTag closingTag = findNextTag(text, bodyEnd);
                cursor = (closingTag != null && closingTag.start() == bodyEnd && closingTag.closing()
                    && "invoke".equals(closingTag.name()))
                    ? closingTag.end()
                    : bodyEnd;
            } else {
                cursor = tag.end();
            }
            tag = findNextTag(text, cursor);
        }
        return List.copyOf(calls);
    }

    private static boolean isAllowedTool(String toolName, Set<String> allowedToolNames) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim();
        return allowedToolNames == null || allowedToolNames.isEmpty() || allowedToolNames.contains(normalized);
    }

    private static int skipBlockEnd(String text, DsmlTag opening) {
        if (opening.closing()) {
            return opening.end();
        }
        if (WRAPPER_TAGS.contains(opening.name()) || "invoke".equals(opening.name())) {
            int closeStart = findMatchingCloseStart(text, opening.end(), opening.name());
            DsmlTag closing = findNextTag(text, closeStart);
            if (closing != null && closing.start() == closeStart && closing.closing()
                && opening.name().equals(closing.name())) {
                return closing.end();
            }
            return text.length();
        }
        return opening.end();
    }

    private static int findMatchingCloseStart(String text, int from, String tagName) {
        DsmlTag next = findNextTag(text, from);
        while (next != null) {
            if (next.closing() && tagName.equals(next.name())) {
                return next.start();
            }
            if ("invoke".equals(tagName) && !next.closing() && "invoke".equals(next.name())) {
                return next.start();
            }
            if ("invoke".equals(tagName) && next.closing() && WRAPPER_TAGS.contains(next.name())) {
                return next.start();
            }
            next = findNextTag(text, next.end());
        }
        return text.length();
    }

    private static String parseParametersJson(String invokeBody) {
        Map<String, Object> params = new LinkedHashMap<>();
        DsmlTag tag = findNextTag(invokeBody, 0);
        while (tag != null) {
            if (!tag.closing() && "parameter".equals(tag.name())) {
                String paramName = parseAttribute(tag.attributes(), "name");
                String stringFlag = parseAttribute(tag.attributes(), "string");
                int valueEnd = findMatchingCloseStart(invokeBody, tag.end(), "parameter");
                String rawValue = invokeBody.substring(tag.end(), valueEnd);
                if (paramName != null && !paramName.isBlank()) {
                    params.put(paramName.trim(), decodeParameterValue(rawValue, stringFlag));
                }
                DsmlTag closing = findNextTag(invokeBody, valueEnd);
                int nextCursor = (closing != null && closing.start() == valueEnd && closing.closing()
                    && "parameter".equals(closing.name()))
                    ? closing.end()
                    : valueEnd;
                tag = findNextTag(invokeBody, nextCursor);
            } else {
                tag = findNextTag(invokeBody, tag.end());
            }
        }
        if (params.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private static Object decodeParameterValue(String rawValue, String stringFlag) {
        if ("false".equalsIgnoreCase(stringFlag == null ? "" : stringFlag.trim())) {
            String trimmed = rawValue.trim();
            if (!trimmed.isEmpty()) {
                try {
                    JsonNode parsed = OBJECT_MAPPER.readTree(trimmed);
                    return OBJECT_MAPPER.treeToValue(parsed, Object.class);
                } catch (JsonProcessingException ignored) {
                    return trimmed;
                }
            }
        }
        return rawValue;
    }

    private static String parseAttribute(String attributes, String targetName) {
        if (attributes == null || attributes.isBlank()) {
            return null;
        }
        int index = 0;
        int len = attributes.length();
        while (index < len) {
            index = skipWhitespace(attributes, index);
            int nameStart = index;
            index = scanNameEnd(attributes, index);
            String attrName = attributes.substring(nameStart, index);
            index = skipWhitespace(attributes, index);
            if (index >= len || attributes.charAt(index) != '=') {
                continue;
            }
            index = skipWhitespace(attributes, index + 1);
            ParsedValue parsed = readAttributeValue(attributes, index);
            if (parsed == null) {
                return null;
            }
            if (targetName.equalsIgnoreCase(attrName)) {
                return parsed.value();
            }
            index = parsed.nextIndex();
        }
        return null;
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int scanNameEnd(String text, int index) {
        int i = index;
        while (i < text.length() && text.charAt(i) != '=' && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static ParsedValue readAttributeValue(String attributes, int start) {
        int len = attributes.length();
        if (start >= len) {
            return null;
        }
        char quote = attributes.charAt(start);
        if (quote == '"' || quote == '\'') {
            int closeQuote = attributes.indexOf(quote, start + 1);
            if (closeQuote < 0) {
                return null;
            }
            return new ParsedValue(attributes.substring(start + 1, closeQuote), closeQuote + 1);
        }
        int end = start;
        while (end < len && !Character.isWhitespace(attributes.charAt(end))) {
            end++;
        }
        return new ParsedValue(attributes.substring(start, end), end);
    }

    private record ParsedValue(String value, int nextIndex) {
    }

    static DsmlTag findNextTag(String text, int from) {
        int index = Math.max(0, from);
        int len = text.length();
        while (index < len) {
            int lt = text.indexOf('<', index);
            if (lt < 0) {
                return null;
            }
            DsmlTag candidate = tryParseTagAt(text, lt);
            if (candidate != null) {
                return candidate;
            }
            index = lt + 1;
        }
        return null;
    }

    private static DsmlTag tryParseTagAt(String text, int start) {
        int len = text.length();
        int i = start + 1;
        boolean closing = false;
        if (i < len && text.charAt(i) == '/') {
            closing = true;
            i++;
        }
        int pipeStart = i;
        while (i < len && isPipe(text.charAt(i))) {
            i++;
        }
        if (i == pipeStart) {
            return null;
        }
        if (i + MARKER.length() > len || !text.regionMatches(true, i, MARKER, 0, MARKER.length())) {
            return null;
        }
        i += MARKER.length();
        int pipeAfterStart = i;
        while (i < len && isPipe(text.charAt(i))) {
            i++;
        }
        if (i == pipeAfterStart) {
            return null;
        }
        int gt = text.indexOf('>', i);
        if (gt < 0) {
            return null;
        }
        String inside = text.substring(i, gt).trim();
        if (inside.isEmpty()) {
            return null;
        }
        int space = findFirstWhitespace(inside);
        String name = (space < 0 ? inside : inside.substring(0, space)).toLowerCase(Locale.ROOT);
        String attributes = space < 0 ? "" : inside.substring(space + 1).trim();
        return new DsmlTag(start, gt + 1, closing, name, attributes);
    }

    private static boolean isPotentialDsmlPrefix(String text, int start) {
        int len = text.length();
        int i = start + 1;
        if (i < len && text.charAt(i) == '/') {
            i++;
        }
        if (i >= len) {
            return true;
        }
        int pipeStart = i;
        while (i < len && isPipe(text.charAt(i))) {
            i++;
        }
        if (i == pipeStart) {
            return false;
        }
        if (i >= len) {
            return true;
        }
        int remaining = len - i;
        if (remaining < MARKER.length()) {
            return text.regionMatches(true, i, MARKER, 0, remaining);
        }
        if (!text.regionMatches(true, i, MARKER, 0, MARKER.length())) {
            return false;
        }
        i += MARKER.length();
        if (i >= len) {
            return true;
        }
        int pipeAfter = i;
        while (i < len && isPipe(text.charAt(i))) {
            i++;
        }
        if (i == pipeAfter) {
            return false;
        }
        return text.indexOf('>', i) < 0;
    }

    private static boolean isPipe(char ch) {
        return ch == '|' || ch == FULLWIDTH_PIPE;
    }

    private static int findFirstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeSpacing(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        int consecutiveNewlines = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines <= 2) {
                    out.append(ch);
                }
            } else {
                consecutiveNewlines = 0;
                out.append(ch);
            }
        }
        return out.toString();
    }

    record DsmlTag(int start, int end, boolean closing, String name, String attributes) {
    }

    /**
     * Stateful streaming filter that forwards normal text immediately with zero buffering,
     * while suppressing leaked DSML markup so raw tool XML never reaches the user UI.
     */
    public static final class StreamFilter {

        private final StringBuilder pending = new StringBuilder();
        private String suppressedBlockTag;
        private boolean sawDsml;

        public boolean sawDsml() {
            return sawDsml;
        }

        public void accept(String delta, Consumer<String> emit) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (suppressedBlockTag == null && pending.isEmpty() && delta.indexOf('<') < 0) {
                emit.accept(delta);
                return;
            }
            pending.append(delta);
            drain(emit, false);
        }

        public void finish(Consumer<String> emit) {
            drain(emit, true);
        }

        private void drain(Consumer<String> emit, boolean endOfStream) {
            while (!pending.isEmpty()) {
                boolean progressed = suppressedBlockTag != null
                    ? drainSuppressed(endOfStream)
                    : drainNormal(emit, endOfStream);
                if (!progressed) {
                    return;
                }
            }
        }

        private boolean drainSuppressed(boolean endOfStream) {
            String buf = pending.toString();
            DsmlTag tag = findNextTag(buf, 0);
            if (tag != null) {
                pending.delete(0, tag.end());
                if (tag.closing() && suppressedBlockTag.equals(tag.name())) {
                    suppressedBlockTag = null;
                }
                return true;
            }
            int lastLt = buf.lastIndexOf('<');
            if (endOfStream) {
                pending.setLength(0);
            } else if (lastLt > 0) {
                pending.delete(0, lastLt);
            }
            return false;
        }

        private boolean drainNormal(Consumer<String> emit, boolean endOfStream) {
            String buf = pending.toString();
            int lt = buf.indexOf('<');
            if (lt < 0) {
                emit.accept(buf);
                pending.setLength(0);
                return false;
            }
            if (lt > 0) {
                emit.accept(buf.substring(0, lt));
                pending.delete(0, lt);
                return true;
            }
            DsmlTag tag = tryParseTagAt(buf, 0);
            if (tag != null) {
                sawDsml = true;
                pending.delete(0, tag.end());
                if (!tag.closing() && (WRAPPER_TAGS.contains(tag.name()) || "invoke".equals(tag.name()))) {
                    suppressedBlockTag = tag.name();
                }
                return true;
            }
            if (!endOfStream && isPotentialDsmlPrefix(buf, 0)) {
                return false;
            }
            if (endOfStream && isPotentialDsmlPrefix(buf, 0) && buf.length() > 2) {
                sawDsml = true;
                pending.setLength(0);
                return false;
            }
            emit.accept("<");
            pending.deleteCharAt(0);
            return true;
        }
    }
}
