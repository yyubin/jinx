package org.jinx.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RelationshipType enum")
class RelationshipTypeTest {

    private static final List<RelationshipType> EXPECTED_ORDER = List.of(
            RelationshipType.MANY_TO_ONE,
            RelationshipType.ONE_TO_MANY,
            RelationshipType.ONE_TO_ONE,
            RelationshipType.MANY_TO_MANY,
            RelationshipType.JOINED_INHERITANCE,
            RelationshipType.SECONDARY_TABLE,
            RelationshipType.ELEMENT_COLLECTION
    );

    @Test
    @DisplayName("values() returns all constants in declared order")
    void values_shouldMatchDeclaredOrder() {
        RelationshipType[] values = RelationshipType.values();
        assertEquals(EXPECTED_ORDER.size(), values.length, "Unexpected number of enum constants");
        assertIterableEquals(EXPECTED_ORDER, Arrays.asList(values), "Enum order/contents changed unexpectedly");
    }

    @Test
    @DisplayName("All enum constants are unique (no duplicates)")
    void constants_shouldBeUnique() {
        Set<RelationshipType> set = EnumSet.copyOf(Arrays.asList(RelationshipType.values()));
        assertEquals(RelationshipType.values().length, set.size(), "Duplicate enum constants detected");
    }

    @Test
    @DisplayName("valueOf returns the correct constant for each valid name")
    void valueOf_shouldReturnCorrectConstant() {
        for (RelationshipType type : RelationshipType.values()) {
            String name = type.name();
            RelationshipType parsed = RelationshipType.valueOf(name);
            assertSame(type, parsed, "valueOf did not return the same enum constant for name=" + name);
        }
    }

    @Test
    @DisplayName("valueOf throws IllegalArgumentException for invalid name")
    void valueOf_shouldThrowForInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> RelationshipType.valueOf("NOT_A_VALID_RELATIONSHIP"));
        assertThrows(IllegalArgumentException.class, () -> RelationshipType.valueOf("many_to_one")); // wrong case
        assertThrows(IllegalArgumentException.class, () -> RelationshipType.valueOf("")); // empty
        // Whitespace not trimmed by valueOf
        assertThrows(IllegalArgumentException.class, () -> RelationshipType.valueOf(" MANY_TO_ONE "));
    }

    @Test
    @DisplayName("valueOf throws NullPointerException for null input")
    void valueOf_shouldThrowForNull() {
        assertThrows(NullPointerException.class, () -> RelationshipType.valueOf(null));
    }

    @Test
    @DisplayName("toString matches name() for each constant (no custom override)")
    void toString_shouldMatchName() {
        for (RelationshipType type : RelationshipType.values()) {
            assertEquals(type.name(), type.toString(), "toString differs from name for " + type);
        }
    }

    @Nested
    @DisplayName("Sanity checks for specific constants")
    class SpecificConstantsTest {

        @Test
        @DisplayName("MANY_TO_ONE constant basics")
        void manyToOne_basic() {
            assertEquals("MANY_TO_ONE", RelationshipType.MANY_TO_ONE.name());
            assertSame(RelationshipType.MANY_TO_ONE, RelationshipType.valueOf("MANY_TO_ONE"));
        }

        @Test
        @DisplayName("ONE_TO_MANY constant basics")
        void oneToMany_basic() {
            assertEquals("ONE_TO_MANY", RelationshipType.ONE_TO_MANY.name());
            assertSame(RelationshipType.ONE_TO_MANY, RelationshipType.valueOf("ONE_TO_MANY"));
        }

        @Test
        @DisplayName("ONE_TO_ONE constant basics")
        void oneToOne_basic() {
            assertEquals("ONE_TO_ONE", RelationshipType.ONE_TO_ONE.name());
            assertSame(RelationshipType.ONE_TO_ONE, RelationshipType.valueOf("ONE_TO_ONE"));
        }

        @Test
        @DisplayName("MANY_TO_MANY constant basics")
        void manyToMany_basic() {
            assertEquals("MANY_TO_MANY", RelationshipType.MANY_TO_MANY.name());
            assertSame(RelationshipType.MANY_TO_MANY, RelationshipType.valueOf("MANY_TO_MANY"));
        }

        @Test
        @DisplayName("JOINED_INHERITANCE constant basics")
        void joinedInheritance_basic() {
            assertEquals("JOINED_INHERITANCE", RelationshipType.JOINED_INHERITANCE.name());
            assertSame(RelationshipType.JOINED_INHERITANCE, RelationshipType.valueOf("JOINED_INHERITANCE"));
        }

        @Test
        @DisplayName("SECONDARY_TABLE constant basics")
        void secondaryTable_basic() {
            assertEquals("SECONDARY_TABLE", RelationshipType.SECONDARY_TABLE.name());
            assertSame(RelationshipType.SECONDARY_TABLE, RelationshipType.valueOf("SECONDARY_TABLE"));
        }

        @Test
        @DisplayName("ELEMENT_COLLECTION constant basics")
        void elementCollection_basic() {
            assertEquals("ELEMENT_COLLECTION", RelationshipType.ELEMENT_COLLECTION.name());
            assertSame(RelationshipType.ELEMENT_COLLECTION, RelationshipType.valueOf("ELEMENT_COLLECTION"));
        }
    }
}