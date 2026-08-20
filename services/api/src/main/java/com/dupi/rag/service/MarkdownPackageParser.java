package com.dupi.rag.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pure ZIP parser: no durable mutation is possible until every entry and reference validates. */
@org.springframework.stereotype.Component
public class MarkdownPackageParser {
    static final int MAX_ENTRIES = 1_000;
    static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    static final int MAX_ENTRY_BYTES = 50 * 1024 * 1024;
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "!\\[[^]\\n]*]\\((?:<([^>\\n]+)>|([^\\s)\\n]+))");
    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    public MarkdownImportPlan parse(java.util.UUID jobId, java.util.UUID kbId, InputStream input) {
        if (jobId == null || kbId == null || input == null) {
            throw new IllegalArgumentException("Markdown package requires job, knowledge base, and ZIP input");
        }
        Map<String, RawEntry> raw = readArchive(input);
        List<RawEntry> markdown = raw.values().stream().filter(entry -> isMarkdown(entry.path())).toList();
        if (markdown.isEmpty()) throw new IllegalArgumentException("Markdown package contains no .md files");

        List<MarkdownImportPlan.Entry> entries = raw.values().stream().map(entry ->
                new MarkdownImportPlan.Entry(entry.path(), mimeType(entry.path()), entry.content().length,
                        sha256(entry.content()), stagingKey(jobId, entry.path()), entry.content())).toList();
        List<MarkdownImportPlan.MarkdownDocument> documents = new ArrayList<>();
        for (RawEntry source : markdown) {
            java.util.UUID documentId = MarkdownImportPlan.deterministicId(jobId, "document", source.path());
            List<MarkdownImportPlan.Asset> assets = new ArrayList<>();
            for (String reference : imageReferences(new String(source.content(), StandardCharsets.UTF_8))) {
                String assetPath = resolveAssetPath(source.path(), reference);
                RawEntry asset = raw.get(assetPath);
                if (asset == null) {
                    throw new IllegalArgumentException("Markdown image is missing from package: " + reference);
                }
                if (!isImage(asset.path())) {
                    throw new IllegalArgumentException("Markdown image has an unsupported type: " + reference);
                }
                String identity = source.path() + "\u0000" + reference;
                java.util.UUID assetId = MarkdownImportPlan.deterministicId(jobId, "asset", identity);
                String fileName = fileName(asset.path());
                assets.add(new MarkdownImportPlan.Asset(assetId, reference, asset.path(), fileName,
                        mimeType(asset.path()), kbId + "/" + documentId + "/assets/" + assetId + "/" + fileName,
                        asset.content().length, sha256(asset.content()), stagingKey(jobId, asset.path())));
            }
            String name = fileName(source.path());
            documents.add(new MarkdownImportPlan.MarkdownDocument(documentId, source.path(), name,
                    kbId + "/" + documentId + "/" + name, source.content().length, sha256(source.content()),
                    stagingKey(jobId, source.path()), assets));
        }
        String packageSha = sha256(entries.stream().map(entry -> entry.path() + "\u0000" + entry.sha256() + "\n")
                .sorted().collect(java.util.stream.Collectors.joining()).getBytes(StandardCharsets.UTF_8));
        return new MarkdownImportPlan(jobId, kbId, packageSha, entries, documents);
    }

    private Map<String, RawEntry> readArchive(InputStream input) {
        Map<String, RawEntry> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.isDirectory()) continue;
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("Markdown package contains too many files");
                String path = normalizeArchivePath(entry.getName());
                byte[] content = readEntry(zip);
                total += content.length;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Markdown package is too large after extraction");
                }
                if (!isMarkdown(path) && !isImage(path)) {
                    throw new IllegalArgumentException("Markdown package contains an unsupported file type: " + path);
                }
                if (entries.putIfAbsent(path, new RawEntry(path, content)) != null) {
                    throw new IllegalArgumentException("Markdown package contains duplicate path: " + path);
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read Markdown package", exception);
        }
        return entries;
    }

    private byte[] readEntry(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            total += read;
            if (total > MAX_ENTRY_BYTES) throw new IllegalArgumentException("Markdown package entry exceeds 50 MB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String normalizeArchivePath(String value) {
        String raw = value == null ? "" : value.replace('\\', '/');
        if (raw.isBlank() || raw.startsWith("/") || raw.matches("^[A-Za-z]:.*") || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Markdown package contains an invalid path");
        }
        Path normalized;
        try { normalized = Path.of(raw).normalize(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Markdown package contains an invalid path: " + value); }
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Markdown package contains an unsafe path: " + value);
        }
        String result = normalized.toString().replace('\\', '/');
        if (result.isBlank() || ".".equals(result)) throw new IllegalArgumentException("Markdown package contains an invalid path");
        if (result.length() > 2048 || fileName(result).length() > 512) {
            throw new IllegalArgumentException("Markdown package path is too long: " + value);
        }
        return result;
    }

    private List<String> imageReferences(String markdown) {
        List<String> references = new ArrayList<>();
        Matcher matcher = IMAGE_REFERENCE.matcher(markdown);
        while (matcher.find()) {
            String reference = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (reference != null) {
                reference = reference.trim();
                if (reference.length() > 2048) throw new IllegalArgumentException("Markdown image path is too long");
                if (!reference.isBlank() && !reference.startsWith("#") && !reference.startsWith("//")
                        && !isExternal(reference) && !references.contains(reference)) {
                    references.add(reference);
                }
            }
        }
        return references;
    }

    private String resolveAssetPath(String markdownPath, String reference) {
        String withoutSuffix = reference.split("[?#]", 2)[0].replace('\\', '/');
        try { withoutSuffix = new java.net.URI(withoutSuffix).getPath(); }
        catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Markdown image has an invalid path: " + reference);
        }
        Path parent = Path.of(markdownPath).getParent();
        Path resolved = (parent == null ? Path.of(withoutSuffix) : parent.resolve(withoutSuffix)).normalize();
        if (resolved.isAbsolute() || resolved.startsWith("..")) {
            throw new IllegalArgumentException("Markdown image points outside the package: " + reference);
        }
        return resolved.toString().replace('\\', '/');
    }

    private boolean isExternal(String reference) {
        return EXTERNAL_REFERENCE.matcher(reference).find()
                && !reference.matches("^[A-Za-z]:[\\\\/].*");
    }

    private boolean isMarkdown(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private boolean isImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private String mimeType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (isMarkdown(lower)) return "text/markdown";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String stagingKey(java.util.UUID jobId, String path) {
        return "markdown-imports/" + jobId + "/staging/" + sha256(path.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private record RawEntry(String path, byte[] content) { }
}
