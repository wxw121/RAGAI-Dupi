package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.dto.MarkdownPackageUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class MarkdownPackageService {

    private static final int MAX_ENTRIES = 1_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 50 * 1024 * 1024;
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "!\\[[^]\\n]*]\\((?:<([^>\\n]+)>|([^\\s)\\n]+))");
    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    private final DocumentService documentService;
    private final DocumentAssetService assetService;

    public MarkdownPackageUploadResponse upload(java.util.UUID kbId, MultipartFile archive) {
        if (archive == null || archive.isEmpty()) {
            throw new IllegalArgumentException("Markdown package is empty");
        }
        String fileName = archive.getOriginalFilename() == null ? "" : archive.getOriginalFilename();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("Markdown package must be a ZIP file");
        }

        Map<String, byte[]> entries = readArchive(archive);
        List<String> markdownEntries = entries.keySet().stream()
                .filter(this::isMarkdown)
                .toList();
        if (markdownEntries.isEmpty()) {
            throw new IllegalArgumentException("Markdown package contains no .md files");
        }

        List<DocumentResponse> documents = new ArrayList<>();
        int assetCount = 0;
        for (String markdownPath : markdownEntries) {
            byte[] markdown = entries.get(markdownPath);
            DocumentResponse response = documentService.upload(
                    kbId,
                    new ArchiveMultipartFile(markdownPath, "text/markdown", markdown)
            );
            documents.add(response);
            Document document = documentService.findOrThrow(kbId, response.getId());
            for (String reference : imageReferences(new String(markdown, java.nio.charset.StandardCharsets.UTF_8))) {
                String assetPath = resolveAssetPath(markdownPath, reference);
                byte[] asset = entries.get(assetPath);
                if (asset == null || !isSupportedImage(assetPath)) {
                    continue;
                }
                assetService.register(
                        document,
                        reference,
                        assetPath,
                        imageMimeType(assetPath),
                        asset
                );
                assetCount += 1;
            }
        }
        return MarkdownPackageUploadResponse.builder()
                .documents(documents)
                .assetCount(assetCount)
                .build();
    }

    private Map<String, byte[]> readArchive(MultipartFile archive) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(archive.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                count += 1;
                if (count > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Markdown package contains too many files");
                }
                String path = normalizeArchivePath(entry.getName());
                byte[] content = readEntry(zip);
                total += content.length;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Markdown package is too large after extraction");
                }
                if (entries.putIfAbsent(path, content) != null) {
                    throw new IllegalArgumentException("Markdown package contains duplicate path: " + path);
                }
            }
            return entries;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read Markdown package", ex);
        }
    }

    private byte[] readEntry(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Markdown package entry exceeds 50 MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String normalizeArchivePath(String value) {
        String raw = value == null ? "" : value.replace('\\', '/');
        if (raw.isBlank() || raw.startsWith("/") || raw.matches("^[A-Za-z]:.*") || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Markdown package contains an invalid path");
        }
        Path normalized = Path.of(raw).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Markdown package contains an unsafe path: " + value);
        }
        return normalized.toString().replace('\\', '/');
    }

    private List<String> imageReferences(String markdown) {
        List<String> references = new ArrayList<>();
        Matcher matcher = IMAGE_REFERENCE.matcher(markdown);
        while (matcher.find()) {
            String reference = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (reference != null && !reference.isBlank()
                    && !reference.startsWith("#")
                    && !EXTERNAL_REFERENCE.matcher(reference).find()) {
                references.add(reference.trim());
            }
        }
        return references.stream().distinct().toList();
    }

    private String resolveAssetPath(String markdownPath, String reference) {
        String withoutSuffix = reference.split("[?#]", 2)[0].replace('\\', '/');
        Path parent = Path.of(markdownPath).getParent();
        Path resolved = (parent == null ? Path.of(withoutSuffix) : parent.resolve(withoutSuffix)).normalize();
        if (resolved.isAbsolute() || resolved.startsWith("..")) {
            throw new IllegalArgumentException("Markdown image points outside the package: " + reference);
        }
        return resolved.toString().replace('\\', '/');
    }

    private boolean isMarkdown(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private boolean isSupportedImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private String imageMimeType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static final class ArchiveMultipartFile implements MultipartFile {
        private final String name;
        private final String contentType;
        private final byte[] content;

        private ArchiveMultipartFile(String name, String contentType, byte[] content) {
            this.name = name;
            this.contentType = contentType;
            this.content = content;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(java.io.File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
