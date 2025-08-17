package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ConstraintModel;

public record ConstraintAddContributor(String table, ConstraintModel cons) implements DdlContributor {
    @Override
    public int priority() {
        return 60; // Constraint Add
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getAddConstraintSql(table, cons));
    }
}