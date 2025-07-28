// package org.jinx.migration.internal.alter.RelationshipDropContributor;
package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.RelationshipModel;

public record RelationshipDropContributor(String table, RelationshipModel rel) implements SqlContributor {
    @Override
    public int priority() {
        return 30; // Relationship Drop
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropRelationshipSql(table, rel));
    }
}