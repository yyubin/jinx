package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.*;
import org.jinx.model.ColumnModel;

import java.util.List;

public class LiquibaseUtils {
    public static Constraints buildConstraints(ColumnModel col, String tableName) {
        return Constraints.builder()
                .primaryKey(col.isPrimaryKey() || col.isManualPrimaryKey() ? true : null)
                .primaryKeyName(col.isPrimaryKey() ? "pk_" + tableName + "_" + col.getColumnName() : null)
                .nullable(col.isNullable() ? null : false)
                .unique(col.isUnique() ? true : null)
                .uniqueConstraintName(col.isUnique() ? "uk_" + tableName + "_" + col.getColumnName() : null)
                .build();
    }

    public static ChangeSetWrapper createChangeSet(String id, List<Object> changes) {
        return ChangeSetWrapper.builder()
                .changeSet(ChangeSet.builder()
                        .id(id)
                        .author("auto-generated")
                        .changes(changes)
                        .build())
                .build();
    }

    public static Constraints buildConstraintsWithoutPK(ColumnModel col, String tableName) {
        return Constraints.builder()
                .nullable(col.isNullable() ? null : false)
                .unique(col.isUnique() ? true : null)
                .uniqueConstraintName(col.isUnique() ? "uk_" + tableName + "_" + col.getColumnName() : null)
                .build();
    }
}