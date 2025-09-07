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
        // nullable/unique 값이 null일 수 있다는 전제에서 null-safe 처리
        final boolean isNullableFalse = Boolean.FALSE.equals(col.isNullable());
        final boolean isUniqueTrue    = Boolean.TRUE.equals(col.isUnique());

        Constraints.ConstraintsBuilder b = Constraints.builder();

        // PK는 제외하고 NOT NULL / UNIQUE만 설정
        if (isNullableFalse) {
            b.nullable(false);          // null이면 미설정, false면 NOT NULL
        }
        if (isUniqueTrue) {
            b.unique(true)
                    .uniqueConstraintName("uk_" + tableName + "_" + col.getColumnName());
        }

        return b.build();
    }
}