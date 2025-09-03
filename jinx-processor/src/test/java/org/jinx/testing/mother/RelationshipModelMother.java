// In a new file: org/jinx/testing/mother/RelationshipModelMother.java
package org.jinx.testing.mother;

import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.testing.util.NamingTestUtil;

import java.util.List;
import java.util.Map;

public final class RelationshipModelMother {
    public static RelationshipModel manyToOne(String attributeName,
                                              String ownerTable,
                                              List<String> fkCols,
                                              String referencedTable,
                                              List<String> referencedPkCols) {
        return RelationshipModel.builder()
                //.name(NamingTestUtil.fk(ownerTable, fkCols, referencedTable, referencedPkCols))
                .sourceAttributeName(attributeName)
                .type(RelationshipType.MANY_TO_ONE)
                .tableName(ownerTable)
                .columns(fkCols)
                .referencedTable(referencedTable)
                .referencedColumns(referencedPkCols)
                .mapsIdBindings(new java.util.HashMap<>()) // Initialize for tests
                .build();
    }
}