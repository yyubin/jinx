package org.jinx.testing.mother;

import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;

public final class EntityModelMother {
    public static EntityModel usersWithPkIdLong() {
        EntityModel e = EntityModel.builder().entityName("User.java").tableName("users").fqcn("com.example.User.java").build();
        e.putColumn(ColumnModel.builder()
                .tableName("users")
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build());
        return e;
    }

    public static EntityModel javaEntity(String fqcn, String table) {
        return EntityModel.builder()
                .entityName(fqcn) // FQCN을 스키마 키로 사용
                .fqcn(fqcn)
                .tableName(table)
                .tableType(EntityModel.TableType.ENTITY)
                .isValid(true)
                .build();
    }

    public static EntityModel javaEntityWithPkIdLong(String fqcn, String table) {
        EntityModel e = javaEntity(fqcn, table);
        e.putColumn(ColumnModel.builder()
                .tableName(table)
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .isNullable(false)
                .build());
        return e;
    }

    public static ColumnModel pkColumn(String tableName, String columnName, String javaType) {
        return ColumnModel.builder()
                .tableName(tableName)
                .columnName(columnName)
                .javaType(javaType)
                .isPrimaryKey(true)
                .isNullable(false)
                .build();
    }
}
