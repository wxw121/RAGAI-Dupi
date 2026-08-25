package com.dupi.rag.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, fully validated Markdown package intent. Entry bytes are never persisted in job JSON. */
public record MarkdownImportPlan(
        UUID jobId,
        UUID knowledgeBaseId,
        String packageSha256,
        List<Entry> entries,
        List<MarkdownDocument> documents
) {
    public MarkdownImportPlan {
        entries = List.copyOf(entries);
        documents = List.copyOf(documents);
    }

    public record Entry(String path, String mimeType, long byteSize, String sha256,
                        String stagingKey, byte[] content) {
        public Entry {
            content = content == null ? null : content.clone();
        }

        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }

    public record MarkdownDocument(UUID documentId, String path, String fileName, String objectKey,
                                   long byteSize, String sha256, String stagingKey,
                                   List<Asset> assets) {
        public MarkdownDocument { assets = List.copyOf(assets); }
    }

    public record Asset(UUID assetId, String reference, String sourcePath, String fileName,
                        String mimeType, String objectKey, long byteSize, String sha256,
                        String stagingKey) { }

    public Map<String, Object> toInput() {
        List<Map<String, Object>> encodedEntries = entries.stream().map(entry -> Map.<String, Object>of(
                "path", entry.path(), "mimeType", entry.mimeType(), "byteSize", entry.byteSize(),
                "sha256", entry.sha256(), "stagingKey", entry.stagingKey())).toList();
        List<Map<String, Object>> encodedDocuments = documents.stream().map(document -> {
            List<Map<String, Object>> assets = document.assets().stream().map(asset -> Map.<String, Object>of(
                    "assetId", asset.assetId().toString(), "reference", asset.reference(),
                    "sourcePath", asset.sourcePath(), "fileName", asset.fileName(),
                    "mimeType", asset.mimeType(), "objectKey", asset.objectKey(),
                    "byteSize", asset.byteSize(), "sha256", asset.sha256(),
                    "stagingKey", asset.stagingKey())).toList();
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("documentId", document.documentId().toString());
            encoded.put("path", document.path()); encoded.put("fileName", document.fileName());
            encoded.put("objectKey", document.objectKey()); encoded.put("byteSize", document.byteSize());
            encoded.put("sha256", document.sha256()); encoded.put("stagingKey", document.stagingKey());
            encoded.put("assets", assets);
            return encoded;
        }).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("jobId", jobId.toString()); input.put("knowledgeBaseId", knowledgeBaseId.toString());
        input.put("packageSha256", packageSha256); input.put("entries", encodedEntries);
        input.put("documents", encodedDocuments);
        return input;
    }

    @SuppressWarnings("unchecked")
    public static MarkdownImportPlan fromInput(Map<String, Object> input) {
        try {
            UUID jobId = UUID.fromString(String.valueOf(input.get("jobId")));
            UUID kbId = UUID.fromString(String.valueOf(input.get("knowledgeBaseId")));
            List<Entry> entries = ((List<Map<String, Object>>) input.get("entries")).stream()
                    .map(value -> new Entry(String.valueOf(value.get("path")),
                            String.valueOf(value.get("mimeType")), ((Number) value.get("byteSize")).longValue(),
                            String.valueOf(value.get("sha256")), String.valueOf(value.get("stagingKey")), null))
                    .toList();
            List<MarkdownDocument> documents = new ArrayList<>();
            for (Map<String, Object> value : (List<Map<String, Object>>) input.get("documents")) {
                List<Asset> assets = ((List<Map<String, Object>>) value.get("assets")).stream().map(asset ->
                        new Asset(UUID.fromString(String.valueOf(asset.get("assetId"))),
                                String.valueOf(asset.get("reference")), String.valueOf(asset.get("sourcePath")),
                                String.valueOf(asset.get("fileName")), String.valueOf(asset.get("mimeType")),
                                String.valueOf(asset.get("objectKey")), ((Number) asset.get("byteSize")).longValue(),
                                String.valueOf(asset.get("sha256")), String.valueOf(asset.get("stagingKey"))))
                        .toList();
                documents.add(new MarkdownDocument(UUID.fromString(String.valueOf(value.get("documentId"))),
                        String.valueOf(value.get("path")), String.valueOf(value.get("fileName")),
                        String.valueOf(value.get("objectKey")), ((Number) value.get("byteSize")).longValue(),
                        String.valueOf(value.get("sha256")), String.valueOf(value.get("stagingKey")), assets));
            }
            return new MarkdownImportPlan(jobId, kbId, String.valueOf(input.get("packageSha256")), entries, documents);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Markdown import operation has an invalid persisted plan", invalid);
        }
    }

    public Entry entry(String path) {
        return entries.stream().filter(entry -> entry.path().equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Markdown plan entry is missing: " + path));
    }

    static UUID deterministicId(UUID jobId, String kind, String path) {
        return UUID.nameUUIDFromBytes((jobId + ":" + kind + ":" + path).getBytes(StandardCharsets.UTF_8));
    }
}
