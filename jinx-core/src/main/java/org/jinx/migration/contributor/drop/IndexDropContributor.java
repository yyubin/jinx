package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.IndexModel;

public record IndexDropContributor(String table, IndexModel index) implements DdlContributor {
    @Override
    public int priority() {
        return 30; // Index Drop
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getDropIndexSql(table, index));
    }
}