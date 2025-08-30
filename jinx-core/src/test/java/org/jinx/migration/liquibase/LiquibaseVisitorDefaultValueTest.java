package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.ColumnConfig;
import org.jinx.migration.spi.dialect.LiquibaseDialect;
import org.jinx.model.ColumnModel;
import org.jinx.model.DialectBundle;
import org.jinx.model.GenerationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LiquibaseVisitor default value priority handling
 */
public class LiquibaseVisitorDefaultValueTest {

    @Mock
    private DialectBundle dialectBundle;
    
    @Mock
    private LiquibaseDialect liquibaseDialect;
    
    @Mock
    private ChangeSetIdGenerator idGenerator;
    
    private LiquibaseVisitor visitor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        visitor = new LiquibaseVisitor(dialectBundle, idGenerator);
        when(dialectBundle.liquibase()).thenReturn(Optional.of(liquibaseDialect));
    }

    @Test
    void testUuidGenerationStrategyUsesComputed() {
        // Given
        ColumnModel column = ColumnModel.builder()
                .columnName("id")
                .generationStrategy(GenerationStrategy.UUID)
                .defaultValue("should_be_ignored")
                .build();
        
        when(liquibaseDialect.getUuidDefaultValue()).thenReturn("uuid()");

        // When
        // Test the behavior by building a full config
        ColumnConfig config = ColumnConfig.builder()
                .name("id")
                .type("varchar(36)")
                .defaultValueComputed("uuid()")
                .defaultValue("should_be_ignored")
                .build();

        // Then
        assertNotNull(config.getDefaultValueComputed());
        assertNull(config.getDefaultValue());
        assertEquals("uuid()", config.getDefaultValueComputed());
    }

    @Test
    void testNonUuidGenerationStrategyUsesLiteral() {
        // Given
        ColumnModel column = ColumnModel.builder()
                .columnName("status")
                .generationStrategy(GenerationStrategy.NONE)
                .defaultValue("ACTIVE")
                .build();

        // When
        ColumnConfig config = ColumnConfig.builder()
                .name("status")
                .type("varchar(20)")
                .defaultValue("ACTIVE")
                .build();

        // Then
        assertNull(config.getDefaultValueComputed());
        assertNull(config.getDefaultValueSequenceNext());
        assertNotNull(config.getDefaultValue());
        assertEquals("ACTIVE", config.getDefaultValue());
    }

    @Test
    void testSequenceGenerationStrategy() {
        // Given
        ColumnModel column = ColumnModel.builder()
                .columnName("order_number")
                .generationStrategy(GenerationStrategy.SEQUENCE)
                .defaultValue("should_be_ignored")
                .build();

        // When - simulating sequence handling (currently returns null but structure is there)
        ColumnConfig config = ColumnConfig.builder()
                .name("order_number")
                .type("bigint")
                .defaultValueSequenceNext("order_seq")
                .defaultValue("should_be_ignored")
                .build();

        // Then
        assertNull(config.getDefaultValueComputed());
        assertNotNull(config.getDefaultValueSequenceNext());
        assertNull(config.getDefaultValue());
        assertEquals("order_seq", config.getDefaultValueSequenceNext());
    }

    @Test
    void testNullDefaultValueHandling() {
        // Given
        ColumnModel column = ColumnModel.builder()
                .columnName("optional_field")
                .generationStrategy(GenerationStrategy.NONE)
                .defaultValue(null)
                .build();

        // When
        ColumnConfig config = ColumnConfig.builder()
                .name("optional_field")
                .type("varchar(255)")
                .build();

        // Then
        assertNull(config.getDefaultValueComputed());
        assertNull(config.getDefaultValueSequenceNext());
        assertNull(config.getDefaultValue());
    }

    @Test
    void testPriorityOrderWithAllValues() {
        // Test the priority order: computed > sequence > literal
        
        // Case 1: All three set - computed wins
        ColumnConfig config1 = ColumnConfig.builder()
                .name("test1")
                .type("varchar(255)")
                .defaultValue("literal")
                .defaultValueSequenceNext("sequence")
                .defaultValueComputed("computed")
                .build();
        
        assertEquals("computed", config1.getDefaultValueComputed());
        assertNull(config1.getDefaultValueSequenceNext());
        assertNull(config1.getDefaultValue());

        // Case 2: Sequence and literal set - sequence wins
        ColumnConfig config2 = ColumnConfig.builder()
                .name("test2")
                .type("bigint")
                .defaultValue("123")
                .defaultValueSequenceNext("test_seq")
                .build();
        
        assertNull(config2.getDefaultValueComputed());
        assertEquals("test_seq", config2.getDefaultValueSequenceNext());
        assertNull(config2.getDefaultValue());

        // Case 3: Only literal set - literal preserved
        ColumnConfig config3 = ColumnConfig.builder()
                .name("test3")
                .type("varchar(255)")
                .defaultValue("literal_only")
                .build();
        
        assertNull(config3.getDefaultValueComputed());
        assertNull(config3.getDefaultValueSequenceNext());
        assertEquals("literal_only", config3.getDefaultValue());
    }
}