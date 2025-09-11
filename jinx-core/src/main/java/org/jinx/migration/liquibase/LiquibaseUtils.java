package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.*;
import org.jinx.model.ColumnModel;

import java.util.List;

public class LiquibaseUtils {
    public static ChangeSetWrapper createChangeSet(String id, List<Change> changes) {
        return ChangeSetWrapper.builder()
                .changeSet(ChangeSet.builder()
                        .id(id)
                        .author("auto-generated")
                        .changes(changes)
                        .build())
                .build();
    }

    public static Constraints buildConstraintsWithoutPK(ColumnModel col, String tableName) {
        Constraints.ConstraintsBuilder b = Constraints.builder();

        final boolean isNullableFalse = Boolean.FALSE.equals(col.isNullable());
        if (isNullableFalse) {
            b.nullable(false);
        }

        return b.build();
    }
}