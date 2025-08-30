package org.jinx.testing.asserts;

import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class RelationshipAssertions {
    private RelationshipAssertions(){}

    public static RelationshipModel assertFk(EntityModel e,
                                             String constraintName,
                                             String table,
                                             List<String> cols,
                                             String refTable,
                                             List<String> refCols,
                                             RelationshipType type) {
        RelationshipModel r = e.getRelationships().get(constraintName);
        assertNotNull(r, "missing relationship " + constraintName + ", keys=" + e.getRelationships().keySet());
        assertEquals(type, r.getType());
        assertEquals(table, r.getTableName());
        assertEquals(cols, r.getColumns());
        assertEquals(refTable, r.getReferencedTable());
        assertEquals(refCols, r.getReferencedColumns());
        return r;
    }
}
