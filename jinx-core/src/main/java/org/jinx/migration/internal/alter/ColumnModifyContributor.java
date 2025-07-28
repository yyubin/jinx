package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.JavaTypeMapper;
import org.jinx.migration.SqlContributor;
import org.jinx.migration.ValueTransformer;
import org.jinx.model.ColumnModel;

public record ColumnModifyContributor(String table, ColumnModel newCol, ColumnModel oldCol) implements SqlContributor {
    @Override
    public int priority() {
        return 50; // Column Modify
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getModifyColumnSql(table, newCol, oldCol));
    }
}