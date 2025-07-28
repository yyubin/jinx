package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.JavaTypeMapper;
import org.jinx.migration.SqlContributor;
import org.jinx.migration.ValueTransformer;
import org.jinx.model.ColumnModel;

public record ColumnRenameContributor(String table, ColumnModel newCol, ColumnModel oldCol) implements SqlContributor {
    @Override
    public int priority() {
        return 50; // Column Modify (same as modify)
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getRenameColumnSql(table, newCol, oldCol));
    }
}