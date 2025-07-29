// package org.jinx.migration.internal.create.RelationshipAddContributor;
package org.jinx.migration.internal.create;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.RelationshipModel;

public record RelationshipAddContributor(String table, RelationshipModel rel) implements SqlContributor {
    @Override
    public int priority() {
        return 60; // Relationship Add
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getAddRelationshipSql(table, rel));
    }
}