package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.ColumnConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnConfigMutualExclusivityTest {

    @Test
    void testInsertDataValueMutualExclusivity() {
        // Should fail when multiple insert values are set
        assertThrows(IllegalStateException.class, () -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .value("string_value")
                    .valueNumeric("123")
                    .build();
        });

        assertThrows(IllegalStateException.class, () -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .value("string_value")
                    .valueComputed("NOW()")
                    .build();
        });

        assertThrows(IllegalStateException.class, () -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .valueNumeric("123")
                    .valueComputed("NOW()")
                    .build();
        });

        assertThrows(IllegalStateException.class, () -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .value("string_value")
                    .valueNumeric("123")
                    .valueComputed("NOW()")
                    .build();
        });
    }

    @Test
    void testInsertDataValuesSingletonSuccess() {
        // Should succeed when only one insert value is set
        assertDoesNotThrow(() -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .value("string_value")
                    .build();
        });

        assertDoesNotThrow(() -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .valueNumeric("123")
                    .build();
        });

        assertDoesNotThrow(() -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .valueComputed("NOW()")
                    .build();
        });
    }

    @Test
    void testDefaultValueAndInsertValueCanCoexist() {
        // Default values (DDL) and insert values should be independent
        assertDoesNotThrow(() -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .defaultValue("default_val")
                    .value("insert_val")
                    .build();
        });

        assertDoesNotThrow(() -> {
            ColumnConfig.builder()
                    .name("test_col")
                    .defaultValueComputed("UUID()")
                    .valueNumeric("123")
                    .build();
        });
    }
}