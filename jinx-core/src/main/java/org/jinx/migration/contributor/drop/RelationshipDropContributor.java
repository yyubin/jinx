// package org.jinx.migration.internal.drop.RelationshipDropContributor;
package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.RelationshipModel;

public record RelationshipDropContributor(String table, RelationshipModel rel) implements DdlContributor {
    @Override
    public int priority() {
        return 30; // Relationship Drop
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getDropRelationshipSql(table, rel));
    }
}