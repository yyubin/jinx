package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.IndexModel;

public record IndexModifyContributor(String table, IndexModel newIndex, IndexModel oldIndex) implements SqlContributor {
    @Override
    public int priority() {
        return 30; // Index Modify (drop first, then add at 60)
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getModifyIndexSql(table, newIndex, oldIndex));
    }
}