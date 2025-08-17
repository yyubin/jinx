// package org.jinx.migration.internal.create.RelationshipAddContributor;
package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.PostCreateContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.RelationshipModel;

public record RelationshipAddContributor(String table, RelationshipModel rel) implements DdlContributor, PostCreateContributor {
    @Override
    public int priority() {
        return 60; // Relationship Add
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getAddRelationshipSql(table, rel));
    }
}