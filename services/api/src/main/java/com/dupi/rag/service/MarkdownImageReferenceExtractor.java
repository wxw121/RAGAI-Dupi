package com.dupi.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Extracts the supported CommonMark image forms without attempting to render Markdown.
 * Accepted forms are inline destinations (angle-delimited or bare with escaped/balanced
 * parentheses) and full, collapsed, or shortcut references backed by a link definition.
 */
final class MarkdownImageReferenceExtractor {

    private final LongConsumer columnVisits;

    MarkdownImageReferenceExtractor() {
        this(ignored -> { });
    }

    MarkdownImageReferenceExtractor(LongConsumer columnVisits) {
        this.columnVisits = Objects.requireNonNull(columnVisits);
    }

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
        FenceContext context = open == null ? fenceContext(line) : null;
        int start = open == null ? context.markerColumn() : closingFenceStart(line, open);
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
            return new Fence(marker, length, context.containers());
        }
        return marker == open.marker() && length >= open.length() && remainder.isBlank() ? open : null;
    }

    private FenceContext fenceContext(String line) {
        List<FenceContainer> containers = new ArrayList<>();
        Position cursor = Position.start();
        int containerColumn = 0;
        while (true) {
            Position marker = skipIndent(line, cursor, containerColumn, 3);
            if (marker.offset() < line.length() && line.charAt(marker.offset()) == '>') {
                cursor = advance(line, marker);
                if (cursor.offset() < line.length() && isWhitespace(line.charAt(cursor.offset()))) {
                    cursor = advance(line, cursor);
                }
                containers.add(FenceContainer.blockquote());
                containerColumn = cursor.column();
                continue;
            }
            ListMarker list = listMarker(line, marker, containerColumn);
            if (list != null) {
                if (!list.validPadding()) return new FenceContext(line.length(), List.copyOf(containers));
                containers.add(FenceContainer.listItem(list.continuationWidth()));
                cursor = list.contentStart();
                containerColumn = cursor.column();
                continue;
            }
            return new FenceContext(marker.offset(), List.copyOf(containers));
        }
    }

    private int closingFenceStart(String line, Fence open) {
        Position cursor = Position.start();
        int containerColumn = 0;
        for (FenceContainer container : open.containers()) {
            if (container.quote()) {
                cursor = skipIndent(line, cursor, containerColumn, 3);
                if (cursor.offset() >= line.length() || line.charAt(cursor.offset()) != '>') {
                    return line.length();
                }
                cursor = advance(line, cursor);
                if (cursor.offset() < line.length() && isWhitespace(line.charAt(cursor.offset()))) {
                    cursor = advance(line, cursor);
                }
                containerColumn = cursor.column();
                continue;
            }
            int targetColumn = containerColumn + container.continuationWidth();
            while (cursor.offset() < line.length() && cursor.column() < targetColumn) {
                if (!isWhitespace(line.charAt(cursor.offset()))) return line.length();
                cursor = advance(line, cursor);
            }
            if (cursor.column() < targetColumn) return line.length();
            containerColumn = targetColumn;
        }
        return skipIndent(line, cursor, containerColumn, 3).offset();
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
        Position cursor = skipUpToThreeSpaces(line, Position.start());
        int quoteDepth = 0;
        boolean consumedContainer;
        do {
            consumedContainer = false;
            if (cursor.offset() < line.length() && line.charAt(cursor.offset()) == '>') {
                quoteDepth++;
                cursor = advance(line, cursor);
                if (cursor.offset() < line.length() && isWhitespace(line.charAt(cursor.offset()))) {
                    cursor = advance(line, cursor);
                }
                cursor = skipUpToThreeSpaces(line, cursor);
                consumedContainer = true;
            }
            ListMarker list = listMarker(line, cursor, cursor.column());
            if (list != null && list.validPadding()) {
                cursor = skipUpToThreeSpaces(line, list.contentStart());
                consumedContainer = true;
            }
        } while (consumedContainer);
        return new LineContext(cursor.offset(), quoteDepth);
    }

    private Position skipUpToThreeSpaces(String line, Position offset) {
        Position cursor = offset;
        int spaces = 0;
        while (cursor.offset() < line.length() && spaces < 3 && line.charAt(cursor.offset()) == ' ') {
            cursor = advance(line, cursor);
            spaces++;
        }
        return cursor;
    }

    private ListMarker listMarker(String line, Position offset, int containerColumn) {
        if (offset.offset() >= line.length()) return null;
        Position markerEnd = offset;
        char first = line.charAt(offset.offset());
        if (first == '-' || first == '+' || first == '*') {
            markerEnd = advance(line, markerEnd);
        } else if (Character.isDigit(first)) {
            while (markerEnd.offset() < line.length() && markerEnd.offset() - offset.offset() < 9
                    && Character.isDigit(line.charAt(markerEnd.offset()))) {
                markerEnd = advance(line, markerEnd);
            }
            if (markerEnd.offset() >= line.length()
                    || (line.charAt(markerEnd.offset()) != '.' && line.charAt(markerEnd.offset()) != ')')) return null;
            markerEnd = advance(line, markerEnd);
        } else {
            return null;
        }
        if (markerEnd.offset() >= line.length() || !isWhitespace(line.charAt(markerEnd.offset()))) return null;
        Position contentStart = markerEnd;
        while (contentStart.offset() < line.length() && isWhitespace(line.charAt(contentStart.offset()))) {
            contentStart = advance(line, contentStart);
        }
        int paddingColumns = contentStart.column() - markerEnd.column();
        int continuationWidth = contentStart.column() - containerColumn;
        return new ListMarker(contentStart, continuationWidth, paddingColumns <= 4);
    }

    private Position skipIndent(String line, Position offset, int baseColumn, int maxColumns) {
        Position cursor = offset;
        while (cursor.offset() < line.length() && isWhitespace(line.charAt(cursor.offset()))) {
            Position next = advance(line, cursor);
            if (next.column() - baseColumn > maxColumns) break;
            cursor = next;
        }
        return cursor;
    }

    private Position advance(String line, Position position) {
        return new Position(position.offset() + 1,
                advanceColumn(position.column(), line.charAt(position.offset())));
    }

    private int advanceColumn(int column, char current) {
        columnVisits.accept(1L);
        return current == '\t' ? column + 4 - column % 4 : column + 1;
    }

    private boolean isWhitespace(char current) {
        return current == ' ' || current == '\t';
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
    private record Fence(char marker, int length, List<FenceContainer> containers) { }
    private record Position(int offset, int column) {
        private static Position start() { return new Position(0, 0); }
    }
    private record ListMarker(Position contentStart, int continuationWidth, boolean validPadding) { }
    private record FenceContainer(boolean quote, int continuationWidth) {
        private static FenceContainer blockquote() { return new FenceContainer(true, 0); }
        private static FenceContainer listItem(int continuationWidth) { return new FenceContainer(false, continuationWidth); }
    }
    private record FenceContext(int markerColumn, List<FenceContainer> containers) { }
    private record LineContext(int contentStart, int quoteDepth) { }
}
