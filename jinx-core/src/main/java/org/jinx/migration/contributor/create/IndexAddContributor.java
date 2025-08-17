package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.PostCreateContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.IndexModel;

public record IndexAddContributor(String table, IndexModel index) implements DdlContributor, PostCreateContributor {
    @Override
    public int priority() {
        return 60; // Index Add
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        // Reusing the existing indexStatement method from the dialect
        sb.append(dialect.indexStatement(index, table));
    }
}