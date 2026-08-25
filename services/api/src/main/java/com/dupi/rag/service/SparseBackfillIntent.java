package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.entity.SparseMigration;

record SparseBackfillIntent(
        SparseMigration migration,
        RetrievalProfile profile,
        int expectedDimension,
        long sourceChunkCount
) { }
