package org.jinx.migration.contributor.alter;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;

public record ColumnRenameContributor(String table, ColumnModel newCol, ColumnModel oldCol) implements DdlContributor {
    @Override
    public int priority() {
        return 50; // Column Modify (same as modify)
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getRenameColumnSql(table, newCol, oldCol));
    }
}