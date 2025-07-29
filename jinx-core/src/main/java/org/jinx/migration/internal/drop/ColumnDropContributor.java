package org.jinx.migration.internal.drop;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ColumnModel;

public record ColumnDropContributor(String table, ColumnModel col) implements SqlContributor {
    @Override
    public int priority() {
        return 20; // Column Drop
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropColumnSql(table, col));
    }
}