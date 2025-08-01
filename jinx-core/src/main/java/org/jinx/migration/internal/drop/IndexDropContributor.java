package org.jinx.migration.internal.drop;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.IndexModel;

public record IndexDropContributor(String table, IndexModel index) implements SqlContributor {
    @Override
    public int priority() {
        return 30; // Index Drop
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropIndexSql(table, index));
    }
}