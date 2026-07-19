package com.dupi.rag.dto.recovery;

import java.util.List;

public record VectorSnapshotPage(
        List<VectorSnapshotRow> rows,
        String nextCursor,
        String payloadChecksum) {
}
