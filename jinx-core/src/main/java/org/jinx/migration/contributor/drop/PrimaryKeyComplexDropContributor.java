package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;

import java.util.Collection;

public record PrimaryKeyComplexDropContributor(String table, Collection<ColumnModel> currentColumns) implements DdlContributor {
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getDropPrimaryKeySql(table, currentColumns));
    }
}
