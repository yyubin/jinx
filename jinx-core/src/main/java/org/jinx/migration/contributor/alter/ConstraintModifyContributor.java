package org.jinx.migration.contributor.alter;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ConstraintModel;

public record ConstraintModifyContributor(String table, ConstraintModel newCons, ConstraintModel oldCons) implements DdlContributor {
    @Override
    public int priority() {
        return 30;
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getModifyConstraintSql(table, newCons, oldCons));
    }
}