package org.jinx.processor;

import org.junit.jupiter.api.Test;

class ValidationTest extends AbstractProcessorTest {

    @Test
    void testEntityWithoutPrimaryKeyShouldFail() {
        assertCompilationError(
                "Entity 'entities.InvalidUser' must have a primary key.",
                source("entities/InvalidUser.java")
        );
    }
}