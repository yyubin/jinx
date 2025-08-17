package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;

public record ColumnDropContributor(String table, ColumnModel col) implements DdlContributor {
    @Override
    public int priority() {
        return 20; // Column Drop
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getDropColumnSql(table, col));
    }
}