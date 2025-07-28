package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ConstraintModel;

public record ConstraintAddContributor(String table, ConstraintModel cons) implements SqlContributor {
    @Override
    public int priority() {
        return 60; // Constraint Add
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getAddConstraintSql(table, cons));
    }
}