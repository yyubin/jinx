package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;

public record ColumnAddContributor(String table, ColumnModel col) implements DdlContributor {
    @Override
    public int priority() {
        return 40; // Column Add
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getAddColumnSql(table, col));
    }
}