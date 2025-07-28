package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ConstraintModel;

public record ConstraintDropContributor(String table, ConstraintModel cons) implements SqlContributor {
    @Override
    public int priority() {
        return 30; // Constraint Drop
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropConstraintSql(table, cons));
    }
}