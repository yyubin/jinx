package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ConstraintModel;

public record ConstraintDropContributor(String table, ConstraintModel cons) implements DdlContributor {
    @Override
    public int priority() {
        return 30; // Constraint Drop
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getDropConstraintSql(table, cons));
    }
}