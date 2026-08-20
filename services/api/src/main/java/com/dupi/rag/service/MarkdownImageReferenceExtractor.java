package com.dupi.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts the supported CommonMark image forms without attempting to render Markdown.
 * Accepted forms are inline destinations (angle-delimited or bare with escaped/balanced
 * parentheses) and full, collapsed, or shortcut references backed by a link definition.
 */
final class MarkdownImageReferenceExtractor {

    List<String> extract(String markdown) {
        Map<String, String> definitions = definitions(markdown);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = 0; index + 1 < markdown.length(); index++) {
            if (markdown.charAt(index) != '!' || markdown.charAt(index + 1) != '[' || escaped(markdown, index)) continue;
            Bracket alt = bracket(markdown, index + 1);
            if (alt == null) continue;
            int next = alt.end() + 1;
            if (next < markdown.length() && markdown.charAt(next) == '(') {
                Destination inline = inlineDestination(markdown, next);
                if (inline != null) {
                    add(targets, inline.value());
                    index = inline.end();
                }
                continue;
            }
            String label = alt.value();
            if (next < markdown.length() && markdown.charAt(next) == '[') {
                Bracket reference = bracket(markdown, next);
                if (reference == null) continue;
                if (!reference.value().isBlank()) label = reference.value();
                index = reference.end();
            } else {
                index = alt.end();
            }
            add(targets, definitions.get(normalizeLabel(label)));
        }
        return new ArrayList<>(targets);
    }

    private Map<String, String> definitions(String markdown) {
        Map<String, String> definitions = new LinkedHashMap<>();
        for (String line : markdown.split("\\R", -1)) {
            int start = 0;
            while (start < line.length() && start < 3 && line.charAt(start) == ' ') start++;
            if (start >= line.length() || line.charAt(start) != '[') continue;
            Bracket label = bracket(line, start);
            if (label == null || label.end() + 1 >= line.length() || line.charAt(label.end() + 1) != ':') continue;
            Destination destination = definitionDestination(line, label.end() + 2);
            if (destination != null && !destination.value().isBlank()) {
                definitions.putIfAbsent(normalizeLabel(label.value()), destination.value());
            }
        }
        return definitions;
    }

    private Destination inlineDestination(String text, int openingParenthesis) {
        int cursor = skipWhitespace(text, openingParenthesis + 1);
        if (cursor >= text.length()) return null;
        if (text.charAt(cursor) == '<') {
            StringBuilder value = new StringBuilder();
            int end = readAngle(text, cursor, value);
            int close = findOuterClose(text, end, openingParenthesis);
            return end < 0 || close < 0 ? null : new Destination(value.toString(), close);
        }
        StringBuilder value = new StringBuilder();
        int depth = 0;
        int cursorAfterValue = cursor;
        for (; cursorAfterValue < text.length(); cursorAfterValue++) {
            char current = text.charAt(cursorAfterValue);
            if (current == '\\' && cursorAfterValue + 1 < text.length()) {
                value.append(text.charAt(++cursorAfterValue));
            } else if (current == '(') {
                depth++;
                value.append(current);
            } else if (current == ')' && depth > 0) {
                depth--;
                value.append(current);
            } else if (current == ')' || Character.isWhitespace(current) && depth == 0) {
                break;
            } else {
                value.append(current);
            }
        }
        int close = findOuterClose(text, cursorAfterValue, openingParenthesis);
        return close < 0 ? null : new Destination(value.toString(), close);
    }

    private Destination definitionDestination(String line, int offset) {
        int cursor = skipWhitespace(line, offset);
        if (cursor >= line.length()) return null;
        StringBuilder value = new StringBuilder();
        if (line.charAt(cursor) == '<') {
            int end = readAngle(line, cursor, value);
            return end < 0 ? null : new Destination(value.toString(), end);
        }
        int depth = 0;
        for (; cursor < line.length(); cursor++) {
            char current = line.charAt(cursor);
            if (current == '\\' && cursor + 1 < line.length()) {
                value.append(line.charAt(++cursor));
            } else if (current == '(') {
                depth++;
                value.append(current);
            } else if (current == ')' && depth > 0) {
                depth--;
                value.append(current);
            } else if (Character.isWhitespace(current) && depth == 0) {
                break;
            } else {
                value.append(current);
            }
        }
        return new Destination(value.toString(), cursor);
    }

    private int readAngle(String text, int opening, StringBuilder value) {
        for (int cursor = opening + 1; cursor < text.length(); cursor++) {
            char current = text.charAt(cursor);
            if (current == '\\' && cursor + 1 < text.length()) value.append(text.charAt(++cursor));
            else if (current == '>') return cursor + 1;
            else if (current == '\n' || current == '\r') return -1;
            else value.append(current);
        }
        return -1;
    }

    private int findOuterClose(String text, int offset, int opening) {
        boolean quoted = false;
        char quote = 0;
        for (int cursor = Math.max(offset, opening + 1); cursor < text.length(); cursor++) {
            char current = text.charAt(cursor);
            if (current == '\\') {
                cursor++;
            } else if (quoted && current == quote) {
                quoted = false;
            } else if (!quoted && (current == '\'' || current == '"')) {
                quoted = true;
                quote = current;
            } else if (!quoted && current == ')') {
                return cursor;
            } else if (current == '\n' || current == '\r') {
                return -1;
            }
        }
        return -1;
    }

    private Bracket bracket(String text, int opening) {
        StringBuilder value = new StringBuilder();
        int depth = 0;
        for (int cursor = opening + 1; cursor < text.length(); cursor++) {
            char current = text.charAt(cursor);
            if (current == '\\' && cursor + 1 < text.length()) value.append(text.charAt(++cursor));
            else if (current == '[') { depth++; value.append(current); }
            else if (current == ']' && depth > 0) { depth--; value.append(current); }
            else if (current == ']') return new Bracket(value.toString(), cursor);
            else if (current == '\n' || current == '\r') return null;
            else value.append(current);
        }
        return null;
    }

    private int skipWhitespace(String text, int offset) {
        int cursor = offset;
        while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) cursor++;
        return cursor;
    }

    private boolean escaped(String text, int offset) {
        int slashes = 0;
        for (int cursor = offset - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) slashes++;
        return slashes % 2 == 1;
    }

    private String normalizeLabel(String label) {
        return label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void add(LinkedHashSet<String> targets, String target) {
        if (target != null && !target.isBlank()) targets.add(target.trim());
    }

    private record Bracket(String value, int end) { }
    private record Destination(String value, int end) { }
}
