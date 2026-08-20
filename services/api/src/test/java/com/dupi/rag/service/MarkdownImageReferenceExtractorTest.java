package com.dupi.rag.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownImageReferenceExtractorTest {

    @Test
    void nestedContainerColumnTrackingVisitsCharactersLinearly() {
        int depth = 2_000;
        String containers = "- ".repeat(depth);
        String continuation = " ".repeat(depth * 2);
        String markdown = containers + "```markdown\n"
                + continuation + "```\n"
                + containers + "[logo]: images/logo.png\n"
                + "![logo]";
        AtomicLong columnVisits = new AtomicLong();

        var targets = new MarkdownImageReferenceExtractor(columnVisits::addAndGet)
                .extract(markdown);

        assertThat(targets).containsExactly("images/logo.png");
        assertThat(columnVisits.get()).isLessThanOrEqualTo(markdown.length() * 12L);
    }
}
