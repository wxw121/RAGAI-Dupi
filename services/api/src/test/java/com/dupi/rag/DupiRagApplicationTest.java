package com.dupi.rag;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class DupiRagApplicationTest {

    @Test
    void mainDelegatesToSpringApplicationWithoutBootstrappingContext() {
        assertThat(new DupiRagApplication()).isNotNull();
        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            String[] args = {"--server.port=0"};

            DupiRagApplication.main(args);

            spring.verify(() -> SpringApplication.run(DupiRagApplication.class, args));
        }
    }
}
