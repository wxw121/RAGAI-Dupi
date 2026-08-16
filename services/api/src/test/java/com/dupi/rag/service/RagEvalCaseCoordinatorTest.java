package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.repository.RagEvalCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvalCaseCoordinatorTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock RagEvalCaseRepository caseRepository;

    @Test
    void loadCasesKeepsAnEmptyEvaluationSetEmpty() {
        UUID kbId = UUID.randomUUID();
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());

        List<RagEvalCase> cases = coordinator().loadCases(kbId);

        verify(knowledgeBaseService).findOrThrow(kbId);
        assertThat(cases).isEmpty();
        verify(caseRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void assertCanCreateLocksKnowledgeBaseAndRejectsTheHundredAndFirstCase() {
        UUID kbId = UUID.randomUUID();
        when(caseRepository.countByKbId(kbId)).thenReturn(100L);

        assertThatThrownBy(() -> coordinator().assertCanCreate(kbId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");

        verify(knowledgeBaseService).findForUpdateOrThrow(kbId);
    }

    private RagEvalCaseCoordinator coordinator() {
        return new RagEvalCaseCoordinator(knowledgeBaseService, caseRepository);
    }
}
