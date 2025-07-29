package org.jinx.migration.internal.create;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ColumnModel;

public record ColumnAddContributor(String table, ColumnModel col) implements SqlContributor {
    @Override
    public int priority() {
        return 40; // Column Add
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getAddColumnSql(table, col));
    }
}