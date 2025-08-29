package org.jinx.migration.contributor.alter;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.IndexModel;

public record IndexModifyContributor(String table, IndexModel newIndex, IndexModel oldIndex) implements DdlContributor {
    @Override
    public int priority() {
        return 30;
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getModifyIndexSql(table, newIndex, oldIndex));
    }
}