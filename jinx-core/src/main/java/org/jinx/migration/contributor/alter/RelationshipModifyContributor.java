// package org.jinx.migration.internal.alter.RelationshipModifyContributor;
package org.jinx.migration.contributor.alter;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.RelationshipModel;

public record RelationshipModifyContributor(String table, RelationshipModel newRel, RelationshipModel oldRel) implements DdlContributor {
    @Override
    public int priority() {
        return 30;
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getModifyRelationshipSql(table, newRel, oldRel));
    }
}