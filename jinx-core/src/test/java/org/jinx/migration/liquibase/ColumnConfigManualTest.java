package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.ColumnConfig;

/**
 * Manual test for ColumnConfig mutual exclusivity without JUnit dependencies
 */
public class ColumnConfigManualTest {
    
    public static void main(String[] args) {
        System.out.println("=== ColumnConfig Mutual Exclusivity Tests ===\n");
        
        // Test 1: Computed has highest priority
        testComputedHasHighestPriority();
        
        // Test 2: Sequence has second priority  
        testSequenceHasSecondPriority();
        
        // Test 3: Literal has lowest priority
        testLiteralHasLowestPriority();
        
        // Test 4: Literal does not override computed (the failing test case)
        testLiteralDoesNotOverrideComputed();
        
        System.out.println("=== All tests completed ===");
    }
    
    private static void testComputedHasHighestPriority() {
        System.out.println("Test 1: Computed has highest priority");
        ColumnConfig config = ColumnConfig.builder()
                .name("test_column")
                .type("varchar(255)")
                .defaultValue("literal_value")
                .defaultValueSequenceNext("test_sequence") 
                .defaultValueComputed("uuid()")
                .build();
        
        boolean passed = config.getDefaultValueComputed() != null &&
                        config.getDefaultValueSequenceNext() == null &&
                        config.getDefaultValue() == null &&
                        "uuid()".equals(config.getDefaultValueComputed());
                        
        System.out.println("Result: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            System.out.println("  Expected: computed='uuid()', sequence=null, literal=null");
            System.out.println("  Actual: computed='" + config.getDefaultValueComputed() + 
                             "', sequence='" + config.getDefaultValueSequenceNext() + 
                             "', literal='" + config.getDefaultValue() + "'");
        }
        System.out.println();
    }
    
    private static void testSequenceHasSecondPriority() {
        System.out.println("Test 2: Sequence has second priority");
        ColumnConfig config = ColumnConfig.builder()
                .name("test_column") 
                .type("bigint")
                .defaultValue("123")
                .defaultValueSequenceNext("test_sequence")
                .build();
        
        boolean passed = config.getDefaultValueComputed() == null &&
                        config.getDefaultValueSequenceNext() != null &&
                        config.getDefaultValue() == null &&
                        "test_sequence".equals(config.getDefaultValueSequenceNext());
                        
        System.out.println("Result: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            System.out.println("  Expected: computed=null, sequence='test_sequence', literal=null");
            System.out.println("  Actual: computed='" + config.getDefaultValueComputed() + 
                             "', sequence='" + config.getDefaultValueSequenceNext() + 
                             "', literal='" + config.getDefaultValue() + "'");
        }
        System.out.println();
    }
    
    private static void testLiteralHasLowestPriority() {
        System.out.println("Test 3: Literal has lowest priority");
        ColumnConfig config = ColumnConfig.builder()
                .name("test_column")
                .type("varchar(255)")
                .defaultValue("literal_value")
                .build();
        
        boolean passed = config.getDefaultValueComputed() == null &&
                        config.getDefaultValueSequenceNext() == null &&
                        config.getDefaultValue() != null &&
                        "literal_value".equals(config.getDefaultValue());
                        
        System.out.println("Result: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            System.out.println("  Expected: computed=null, sequence=null, literal='literal_value'");
            System.out.println("  Actual: computed='" + config.getDefaultValueComputed() + 
                             "', sequence='" + config.getDefaultValueSequenceNext() + 
                             "', literal='" + config.getDefaultValue() + "'");
        }
        System.out.println();
    }
    
    private static void testLiteralDoesNotOverrideComputed() {
        System.out.println("Test 4: Literal does not override computed (original failing test)");
        ColumnConfig config = ColumnConfig.builder()
                .name("test_column")
                .type("varchar(255)")
                .defaultValueComputed("uuid()")
                .defaultValue("should_be_ignored")
                .build();
        
        boolean passed = config.getDefaultValueComputed() != null &&
                        config.getDefaultValueSequenceNext() == null &&
                        config.getDefaultValue() == null &&
                        "uuid()".equals(config.getDefaultValueComputed());
                        
        System.out.println("Result: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            System.out.println("  Expected: computed='uuid()', sequence=null, literal=null");
            System.out.println("  Actual: computed='" + config.getDefaultValueComputed() + 
                             "', sequence='" + config.getDefaultValueSequenceNext() + 
                             "', literal='" + config.getDefaultValue() + "'");
        }
        System.out.println();
    }
}