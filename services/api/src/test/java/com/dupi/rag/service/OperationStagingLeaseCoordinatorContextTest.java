package com.dupi.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OperationStagingLeaseCoordinatorContextTest {

    @Test
    void springSelectsTheProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(OperationStagingLeasePersistence.class,
                    () -> mock(OperationStagingLeasePersistence.class));
            context.register(OperationStagingLeaseCoordinator.class);

            context.refresh();

            assertThat(context.getBean(OperationStagingLeaseCoordinator.class)).isNotNull();
        }
    }
}
