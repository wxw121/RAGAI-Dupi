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
        String visible = withoutCode(markdown);
        Map<String, String> definitions = definitions(visible);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = 0; index + 1 < visible.length(); index++) {
            if (visible.charAt(index) != '!' || visible.charAt(index + 1) != '[' || escaped(visible, index)) continue;
            Bracket alt = bracket(visible, index + 1);
            if (alt == null) continue;
            int next = alt.end() + 1;
            if (next < visible.length() && visible.charAt(next) == '(') {
                Destination inline = inlineDestination(visible, next);
                if (inline != null) {
                    add(targets, inline.value());
                    index = inline.end();
                }
                continue;
            }
            String label = alt.value();
            if (next < visible.length() && visible.charAt(next) == '[') {
                Bracket reference = bracket(visible, next);
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
            int start = contentStart(line);
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

    private String withoutCode(String markdown) {
        char[] visible = markdown.toCharArray();
        maskFencedBlocks(markdown, visible);
        maskCodeSpans(visible);
        return new String(visible);
    }

    private void maskFencedBlocks(String markdown, char[] visible) {
        Fence open = null;
        for (int lineStart = 0; lineStart < markdown.length();) {
            int lineBreak = markdown.indexOf('\n', lineStart);
            int nextLine = lineBreak < 0 ? markdown.length() : lineBreak + 1;
            int lineEnd = lineBreak < 0 ? markdown.length() : lineBreak;
            if (lineEnd > lineStart && markdown.charAt(lineEnd - 1) == '\r') lineEnd--;
            String line = markdown.substring(lineStart, lineEnd);
            Fence candidate = fence(line, open);
            if (open != null) {
                mask(visible, lineStart, lineEnd);
                if (candidate != null) open = null;
            } else if (candidate != null) {
                mask(visible, lineStart, lineEnd);
                open = candidate;
            }
            lineStart = nextLine;
        }
    }

    private Fence fence(String line, Fence open) {
        LineContext context = open == null ? lineContext(line) : null;
        int start = open == null ? context.contentStart() : open.markerColumn();
        if (open != null && !sameContainerPrefix(line, open)) return null;
        if (start >= line.length()) return null;
        char marker = line.charAt(start);
        if (marker != '`' && marker != '~') return null;
        int end = start;
        while (end < line.length() && line.charAt(end) == marker) end++;
        int length = end - start;
        if (length < 3) return null;
        String remainder = line.substring(end);
        if (open == null) {
            if (marker == '`' && remainder.indexOf('`') >= 0) return null;
            return new Fence(marker, length, context.quoteDepth(), start);
        }
        return marker == open.marker() && length >= open.length() && remainder.isBlank() ? open : null;
    }

    private boolean sameContainerPrefix(String line, Fence open) {
        if (line.length() <= open.markerColumn()) return false;
        int quoteDepth = 0;
        for (int cursor = 0; cursor < open.markerColumn(); cursor++) {
            char current = line.charAt(cursor);
            if (current == '>') quoteDepth++;
            else if (current != ' ' && current != '\t') return false;
        }
        return quoteDepth == open.quoteDepth();
    }

    private void maskCodeSpans(char[] visible) {
        for (int index = 0; index < visible.length;) {
            if (visible[index] != '`' || escaped(visible, index)) {
                index++;
                continue;
            }
            int openingLength = runLength(visible, index, '`');
            int closing = findClosingRun(visible, index + openingLength, openingLength);
            if (closing < 0) {
                index += openingLength;
                continue;
            }
            mask(visible, index, closing + openingLength);
            index = closing + openingLength;
        }
    }

    private int findClosingRun(char[] value, int offset, int expectedLength) {
        for (int cursor = offset; cursor < value.length;) {
            if (value[cursor] != '`') {
                cursor++;
                continue;
            }
            int length = runLength(value, cursor, '`');
            if (length == expectedLength) return cursor;
            cursor += length;
        }
        return -1;
    }

    private int runLength(char[] value, int offset, char marker) {
        int end = offset;
        while (end < value.length && value[end] == marker) end++;
        return end - offset;
    }

    private void mask(char[] value, int start, int end) {
        for (int index = start; index < end; index++) {
            if (value[index] != '\r' && value[index] != '\n') value[index] = ' ';
        }
    }

    private int contentStart(String line) {
        return lineContext(line).contentStart();
    }

    private LineContext lineContext(String line) {
        int cursor = skipUpToThreeSpaces(line, 0);
        int quoteDepth = 0;
        boolean consumedContainer;
        do {
            consumedContainer = false;
            if (cursor < line.length() && line.charAt(cursor) == '>') {
                quoteDepth++;
                cursor++;
                if (cursor < line.length() && (line.charAt(cursor) == ' ' || line.charAt(cursor) == '\t')) cursor++;
                cursor = skipUpToThreeSpaces(line, cursor);
                consumedContainer = true;
            }
            int listEnd = listMarkerEnd(line, cursor);
            if (listEnd >= 0) {
                cursor = skipUpToThreeSpaces(line, listEnd);
                consumedContainer = true;
            }
        } while (consumedContainer);
        return new LineContext(cursor, quoteDepth);
    }

    private int skipUpToThreeSpaces(String line, int offset) {
        int cursor = offset;
        int spaces = 0;
        while (cursor < line.length() && spaces < 3 && line.charAt(cursor) == ' ') {
            cursor++;
            spaces++;
        }
        return cursor;
    }

    private int listMarkerEnd(String line, int offset) {
        if (offset >= line.length()) return -1;
        int markerEnd = offset;
        char first = line.charAt(offset);
        if (first == '-' || first == '+' || first == '*') {
            markerEnd++;
        } else if (Character.isDigit(first)) {
            while (markerEnd < line.length() && markerEnd - offset < 9
                    && Character.isDigit(line.charAt(markerEnd))) markerEnd++;
            if (markerEnd >= line.length() || (line.charAt(markerEnd) != '.' && line.charAt(markerEnd) != ')')) return -1;
            markerEnd++;
        } else {
            return -1;
        }
        if (markerEnd >= line.length() || (line.charAt(markerEnd) != ' ' && line.charAt(markerEnd) != '\t')) return -1;
        return markerEnd + 1;
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

    private boolean escaped(char[] text, int offset) {
        int slashes = 0;
        for (int cursor = offset - 1; cursor >= 0 && text[cursor] == '\\'; cursor--) slashes++;
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
    private record Fence(char marker, int length, int quoteDepth, int markerColumn) { }
    private record LineContext(int contentStart, int quoteDepth) { }
}
