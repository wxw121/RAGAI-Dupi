package com.dupi.rag.domain.enums;

public enum RetrievalProfile {
    CLASSIC,
    PARENT_CHILD,
    QA_ASSISTED,
    COMBINED;

    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static RetrievalProfile fromWireValue(String value) {
        for (RetrievalProfile profile : values()) {
            if (profile.wireValue().equals(value) || profile.name().equalsIgnoreCase(value)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unsupported retrieval profile: " + value);
    }
}
