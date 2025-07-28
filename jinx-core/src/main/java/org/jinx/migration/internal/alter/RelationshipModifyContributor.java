// package org.jinx.migration.internal.alter.RelationshipModifyContributor;
package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.RelationshipModel;

public record RelationshipModifyContributor(String table, RelationshipModel newRel, RelationshipModel oldRel) implements SqlContributor {
    @Override
    public int priority() {
        return 30; // Relationship Modify (drop first, then add at 60)
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getModifyRelationshipSql(table, newRel, oldRel));
    }
}