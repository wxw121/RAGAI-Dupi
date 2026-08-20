package com.dupi.rag.service;

/** Result of an object-store atomic create-if-absent operation. */
public record RecoveryObjectWriteResult(boolean created, String versionToken) {
    public RecoveryObjectWriteResult {
        if (created && (versionToken == null || versionToken.isBlank())) {
            throw new IllegalArgumentException("A created recovery object requires version evidence");
        }
        if (!created && versionToken != null) {
            throw new IllegalArgumentException("A lost create race cannot claim winner evidence");
        }
    }

    public static RecoveryObjectWriteResult created(String versionToken) {
        return new RecoveryObjectWriteResult(true, versionToken);
    }

    public static RecoveryObjectWriteResult lostRace() {
        return new RecoveryObjectWriteResult(false, null);
    }
}
