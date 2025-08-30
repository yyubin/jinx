package org.jinx.testing.mother;

import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;

public final class EntityModelMother {
    public static EntityModel usersWithPkIdLong() {
        EntityModel e = EntityModel.builder().entityName("User").tableName("users").build();
        e.putColumn(ColumnModel.builder()
                .tableName("users")
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build());
        return e;
    }
}
