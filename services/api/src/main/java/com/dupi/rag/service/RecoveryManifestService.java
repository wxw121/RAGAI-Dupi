package com.dupi.rag.service;

import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class RecoveryManifestService {
    public static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper;

    public RecoveryManifestService(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public RecoveryManifest seal(RecoveryManifestHeader header, List<RecoveryManifestItem> sourceItems) {
        validateHeader(header);
        List<RecoveryManifestItem> items = sourceItems.stream()
                .sorted(Comparator.comparing(RecoveryManifestItem::itemKey))
                .toList();
        validateItems(items);
        long totalBytes = items.stream().mapToLong(RecoveryManifestItem::byteSize).sum();
        RecoveryManifest unsigned = new RecoveryManifest(header, items.size(), totalBytes, items, null);
        return new RecoveryManifest(header, items.size(), totalBytes, items, sha256(serialize(unsigned)));
    }

    public byte[] serialize(RecoveryManifest manifest) {
        try {
            return mapper.writeValueAsBytes(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize recovery manifest", e);
        }
    }

    public RecoveryManifest parseAndValidate(byte[] bytes) {
        try {
            RecoveryManifest manifest = mapper.readValue(bytes, RecoveryManifest.class);
            RecoveryManifest resealed = seal(manifest.header(), manifest.items());
            if (!resealed.manifestChecksum().equals(manifest.manifestChecksum())
                    || resealed.itemCount() != manifest.itemCount()
                    || resealed.totalBytes() != manifest.totalBytes()) {
                throw new IllegalArgumentException("Recovery manifest checksum or summary mismatch");
            }
            return manifest;
        } catch (IOException e) {
            throw new IllegalArgumentException("Recovery manifest is invalid JSON", e);
        }
    }

    private void validateHeader(RecoveryManifestHeader header) {
        if (header == null || header.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported recovery manifest schema version");
        }
        if (header.archiveId() == null || header.sourceKnowledgeBaseId() == null
                || header.tenantId() == null || header.tenantId().isBlank()
                || header.embeddingModel() == null || header.embeddingModel().isBlank()
                || header.embeddingDimension() <= 0) {
            throw new IllegalArgumentException("Recovery manifest header is incomplete");
        }
    }

    private void validateItems(List<RecoveryManifestItem> items) {
        Set<String> keys = new HashSet<>();
        for (RecoveryManifestItem item : items) {
            if (item == null || item.itemKey() == null || item.itemKey().isBlank()
                    || item.objectKey() == null || item.objectKey().isBlank()
                    || item.sha256() == null || item.sha256().isBlank() || item.byteSize() < 0) {
                throw new IllegalArgumentException("Recovery manifest item is incomplete");
            }
            if (!keys.add(item.itemKey())) {
                throw new IllegalArgumentException("Duplicate recovery manifest item key: " + item.itemKey());
            }
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
