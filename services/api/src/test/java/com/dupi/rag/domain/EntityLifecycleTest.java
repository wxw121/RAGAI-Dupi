package com.dupi.rag.domain;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class EntityLifecycleTest {

    @Test
    void entityLifecycleCallbacksPopulateIdsAndTimestamps() throws Exception {
        KnowledgeBase kb = KnowledgeBase.builder().name("KB").build();
        invoke(kb, "onCreate");
        assertThat(kb.getId()).isNotNull();
        assertThat(kb.getCreatedAt()).isNotNull();
        assertThat(kb.getUpdatedAt()).isNotNull();
        invoke(kb, "onUpdate");
        assertThat(kb.getUpdatedAt()).isNotNull();

        Document doc = Document.builder()
                .kbId(kb.getId())
                .fileName("a.md")
                .objectKey("obj")
                .mimeType("text/markdown")
                .build();
        invoke(doc, "onCreate");
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        invoke(doc, "onUpdate");
        assertThat(doc.getUpdatedAt()).isNotNull();

        IngestJob job = IngestJob.builder().kbId(kb.getId()).docId(doc.getId()).build();
        invoke(job, "onCreate");
        assertThat(job.getId()).isNotNull();
        assertThat(job.getCreatedAt()).isNotNull();
        invoke(job, "onUpdate");
        assertThat(job.getUpdatedAt()).isNotNull();

        Chunk chunk = Chunk.builder().kbId(kb.getId()).docId(doc.getId()).chunkIndex(0).content("c").build();
        invoke(chunk, "onCreate");
        assertThat(chunk.getId()).isNotNull();
        assertThat(chunk.getCreatedAt()).isNotNull();
        assertThat(chunk.getTokenCount()).isZero();
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }
}
