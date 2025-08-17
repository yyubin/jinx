package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.TableContributor;
import org.jinx.migration.spi.dialect.DdlDialect;

public record DropTableStatementContributor(String tableName) implements TableContributor {
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getDropTableSql(tableName));
    }
}