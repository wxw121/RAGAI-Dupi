package com.dupi.rag.service;

import com.dupi.rag.exception.OperationConflictException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Stable stage descriptor stored on the durable intake step. */
final class RecoveryStageEvidence {
    private static final String PREFIX = "recovery-stage:v1:";

    private RecoveryStageEvidence() { }

    static String encode(StoredRecoveryObject object) {
        if (object == null || object.versionToken() == null || object.versionToken().isBlank()) {
            throw new IllegalArgumentException("Recovery stage evidence requires an object version");
        }
        return PREFIX + encodePart(object.bucket()) + ":" + encodePart(object.objectKey()) + ":"
                + object.byteSize() + ":" + object.sha256() + ":" + encodePart(object.versionToken());
    }

    static StoredRecoveryObject decode(String value) {
        try {
            if (value == null || !value.startsWith(PREFIX)) throw new IllegalArgumentException();
            String[] parts = value.substring(PREFIX.length()).split(":", -1);
            if (parts.length != 5) throw new IllegalArgumentException();
            return new StoredRecoveryObject(decodePart(parts[0]), decodePart(parts[1]),
                    Long.parseLong(parts[2]), parts[3], decodePart(parts[4]));
        } catch (RuntimeException invalid) {
            throw new OperationConflictException("Recovery import stage evidence is invalid");
        }
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
