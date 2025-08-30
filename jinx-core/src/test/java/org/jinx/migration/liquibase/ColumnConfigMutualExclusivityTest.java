//package org.jinx.migration.liquibase;
//
//import org.jinx.migration.liquibase.model.ColumnConfig;
//
///**
// * Tests for ColumnConfig default value fields mutual exclusivity
// */
//public class ColumnConfigMutualExclusivityTest {
//
//    @BeforeEach
//    void setUp() {
//        // Clear any existing logs
//    }
//
//    @Test
//    void testComputedHasHighestPriority() {
//        // When all three values are set, computed should win
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("varchar(255)")
//                .defaultValue("literal_value")
//                .defaultValueSequenceNext("test_sequence")
//                .defaultValueComputed("uuid()")
//                .build();
//
//        assertNotNull(config.getDefaultValueComputed());
//        assertNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//        assertEquals("uuid()", config.getDefaultValueComputed());
//    }
//
//    @Test
//    void testSequenceHasSecondPriority() {
//        // When sequence and literal are set, sequence should win
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("bigint")
//                .defaultValue("123")
//                .defaultValueSequenceNext("test_sequence")
//                .build();
//
//        assertNull(config.getDefaultValueComputed());
//        assertNotNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//        assertEquals("test_sequence", config.getDefaultValueSequenceNext());
//    }
//
//    @Test
//    void testLiteralHasLowestPriority() {
//        // When only literal is set, it should be preserved
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("varchar(255)")
//                .defaultValue("literal_value")
//                .build();
//
//        assertNull(config.getDefaultValueComputed());
//        assertNull(config.getDefaultValueSequenceNext());
//        assertNotNull(config.getDefaultValue());
//        assertEquals("literal_value", config.getDefaultValue());
//    }
//
//    @Test
//    void testNullValuesAreIgnored() {
//        // Null values should not trigger validation
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("varchar(255)")
//                .defaultValue("literal_value")
//                .defaultValueSequenceNext(null)
//                .defaultValueComputed(null)
//                .build();
//
//        assertNull(config.getDefaultValueComputed());
//        assertNull(config.getDefaultValueSequenceNext());
//        assertNotNull(config.getDefaultValue());
//        assertEquals("literal_value", config.getDefaultValue());
//    }
//
//    @Test
//    void testAllNullValues() {
//        // When all default values are null, all should remain null
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("varchar(255)")
//                .build();
//
//        assertNull(config.getDefaultValueComputed());
//        assertNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//    }
//
//    @Test
//    void testComputedOverridesExistingSequence() {
//        // Setting computed after sequence should override
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("varchar(255)")
//                .defaultValueSequenceNext("test_sequence")
//                .defaultValueComputed("uuid()")
//                .build();
//
//        assertNotNull(config.getDefaultValueComputed());
//        assertNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//        assertEquals("uuid()", config.getDefaultValueComputed());
//    }
//
//    @Test
//    void testSequenceOverridesExistingLiteral() {
//        // Setting sequence after literal should override
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("bigint")
//                .defaultValue("123")
//                .defaultValueSequenceNext("test_sequence")
//                .build();
//
//        assertNull(config.getDefaultValueComputed());
//        assertNotNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//        assertEquals("test_sequence", config.getDefaultValueSequenceNext());
//    }
//
//    @Test
//    void testLiteralDoesNotOverrideComputed() {
//        // Setting literal after computed should not override
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("varchar(255)")
//                .defaultValueComputed("uuid()")
//                .defaultValue("literal_value")
//                .build();
//
//        assertNotNull(config.getDefaultValueComputed());
//        assertNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//        assertEquals("uuid()", config.getDefaultValueComputed());
//    }
//
//    @Test
//    void testLiteralDoesNotOverrideSequence() {
//        // Setting literal after sequence should not override
//        ColumnConfig config = ColumnConfig.builder()
//                .name("test_column")
//                .type("bigint")
//                .defaultValueSequenceNext("test_sequence")
//                .defaultValue("123")
//                .build();
//
//        assertNull(config.getDefaultValueComputed());
//        assertNotNull(config.getDefaultValueSequenceNext());
//        assertNull(config.getDefaultValue());
//        assertEquals("test_sequence", config.getDefaultValueSequenceNext());
//    }
//}