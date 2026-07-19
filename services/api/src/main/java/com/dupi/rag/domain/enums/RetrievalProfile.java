package com.dupi.rag.domain.enums;

public enum RetrievalProfile {
    CLASSIC,
    PARENT_CHILD,
    QA_ASSISTED,
    COMBINED;

    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
