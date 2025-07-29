package org.jinx.migration.internal.drop;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ColumnModel;

import java.util.Collection;

public record PrimaryKeyComplexDropContributor(String table, Collection<ColumnModel> currentColumns) implements SqlContributor {
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropPrimaryKeySql(table, currentColumns));
    }
}
