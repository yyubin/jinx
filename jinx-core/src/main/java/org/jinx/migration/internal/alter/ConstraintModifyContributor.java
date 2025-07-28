package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ConstraintModel;

public record ConstraintModifyContributor(String table, ConstraintModel newCons, ConstraintModel oldCons) implements SqlContributor {
    @Override
    public int priority() {
        // Drop and Add actions are combined, so priority should align with drop.
        // The individual add/drop priorities within the dialect will handle ordering if needed.
        return 30; // Constraint Modify (drop first)
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getModifyConstraintSql(table, newCons, oldCons));
    }
}