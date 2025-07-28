package org.jinx.migration.internal.drop;

import org.jinx.migration.Dialect;
import org.jinx.migration.DropTableContributor;

public record DropTableStatementContributor(String tableName) implements DropTableContributor {
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropTableSql(tableName));
    }
}