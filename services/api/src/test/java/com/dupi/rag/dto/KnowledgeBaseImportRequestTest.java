package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseImportRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void importRequestAcceptsValidConfigurationAndAppliesDefaults() {
        KnowledgeBaseImportRequest request = new KnowledgeBaseImportRequest();
        KnowledgeBaseImportRequest.KnowledgeBaseSnapshot snapshot =
                new KnowledgeBaseImportRequest.KnowledgeBaseSnapshot();
        snapshot.setName("Restored KB");
        request.setKnowledgeBase(snapshot);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.getSchemaVersion()).isEqualTo(1);
        assertThat(snapshot.getChunkSize()).isEqualTo(512);
        assertThat(snapshot.getChunkOverlap()).isEqualTo(64);
        assertThat(snapshot.getTopK()).isEqualTo(5);
    }

    @Test
    void importRequestRejectsInvalidKnowledgeBaseConfigurationAndTooManyCases() {
        KnowledgeBaseImportRequest request = new KnowledgeBaseImportRequest();
        KnowledgeBaseImportRequest.KnowledgeBaseSnapshot snapshot =
                new KnowledgeBaseImportRequest.KnowledgeBaseSnapshot();
        snapshot.setName(" ");
        snapshot.setChunkSize(128);
        snapshot.setChunkOverlap(128);
        request.setKnowledgeBase(snapshot);
        request.setEvalCases(Collections.nCopies(101, new RagEvalCaseRequest()));

        var violations = validator.validate(request);

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("knowledgeBase.name", "knowledgeBase.validChunkOverlap", "evalCases");
    }

    @Test
    void evalCaseRequestRejectsOversizedKeysQueriesFilesAndTokenLists() {
        RagEvalCaseRequest evalCase = new RagEvalCaseRequest();
        evalCase.setCaseKey("k".repeat(129));
        evalCase.setQuery("q".repeat(4_001));
        evalCase.setExpectedFileName("f".repeat(513));
        evalCase.setMustContainAny(Collections.nCopies(21, "token"));

        var violations = validator.validate(evalCase);

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("caseKey", "query", "expectedFileName", "mustContainAny");

        evalCase.setCaseKey("case");
        evalCase.setQuery("query");
        evalCase.setExpectedFileName(null);
        evalCase.setMustContainAny(List.of(" ", "t".repeat(513)));
        assertThat(validator.validate(evalCase)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("mustContainAny[0].<list element>", "mustContainAny[1].<list element>");
    }

    @Test
    void evalCaseRequestValidatesCategorySpecificAssertions() {
        RagEvalCaseRequest evalCase = new RagEvalCaseRequest();
        evalCase.setCaseKey("scenario");
        evalCase.setQuery("query");

        evalCase.setCategory(RagEvalCaseCategory.REAL_QUERY);
        evalCase.setMinHits(0);
        assertThat(validator.validate(evalCase)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("validCategoryAssertions");

        evalCase.setCategory(RagEvalCaseCategory.HARD_NEGATIVE);
        evalCase.setMinHits(1);
        assertThat(validator.validate(evalCase)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("validCategoryAssertions");

        evalCase.setCategory(RagEvalCaseCategory.MULTI_DOCUMENT);
        evalCase.setMinHits(2);
        evalCase.setExpectedFileName("guide.md");
        assertThat(validator.validate(evalCase)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("validCategoryAssertions");

        evalCase.setCategory(RagEvalCaseCategory.AMBIGUOUS);
        evalCase.setMinHits(1);
        evalCase.setExpectedFileNames(List.of("current.md"));
        assertThat(validator.validate(evalCase)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("validCategoryAssertions");
    }
}
