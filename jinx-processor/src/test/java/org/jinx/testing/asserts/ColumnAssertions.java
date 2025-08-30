package org.jinx.testing.asserts;

import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;

import static org.junit.Assert.*;

import static org.junit.jupiter.api.Assertions.*;

public final class ColumnAssertions {
    public static ColumnModel assertPkNonNull(EntityModel e, String fullKey, String javaType) {
        ColumnModel c = assertExists(e, fullKey);
        assertEquals(javaType, c.getJavaType(), () -> "type mismatch at " + fullKey);
        assertTrue(c.isPrimaryKey(), () -> "expected PK at " + fullKey);
        assertFalse(c.isNullable(), () -> "expected NOT NULL at " + fullKey);
        return c;
    }

    public static ColumnModel assertNonPkWithType(EntityModel e, String fullKey, String javaType) {
        ColumnModel c = assertExists(e, fullKey);
        assertEquals(javaType, c.getJavaType(), () -> "type mismatch at " + fullKey);
        assertFalse(c.isPrimaryKey(), () -> "expected NON-PK at " + fullKey);
        return c;
    }

    public static ColumnModel assertExists(EntityModel e, String fullKey) {
        ColumnModel c = e.getColumns().get(fullKey);
        assertNotNull(c, () -> "missing: " + fullKey + " keys=" + e.getColumns().keySet());
        return c;
    }
}