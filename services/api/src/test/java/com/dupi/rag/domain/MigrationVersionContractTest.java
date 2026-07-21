package com.dupi.rag.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationVersionContractTest {

    @Test
    void flywayMigrationVersionsAreUnique() throws IOException {
        Path migrationDirectory = Path.of("src/main/resources/db/migration");
        try (var files = Files.list(migrationDirectory)) {
            List<String> versions = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.+\\.sql"))
                    .map(name -> name.substring(0, name.indexOf("__")))
                    .toList();

            assertThat(versions).doesNotHaveDuplicates();
        }
    }

    @Test
    void v22BackfillsLegacyNoAnswerCasesAsHardNegatives() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V22__rag_eval_case_categories.sql"));

        assertThat(migration).contains("UPDATE rag_eval_cases")
                .contains("category = 'HARD_NEGATIVE'")
                .contains("min_hits = 0")
                .contains("expected_file_name IS NULL")
                .contains("jsonb_array_length(expected_file_names) = 0")
                .contains("jsonb_array_length(must_contain_any) = 0");
    }
}
