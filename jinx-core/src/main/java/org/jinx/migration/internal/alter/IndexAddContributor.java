package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.JavaTypeMapper;
import org.jinx.migration.SqlContributor;
import org.jinx.migration.ValueTransformer;
import org.jinx.model.IndexModel;

public record IndexAddContributor(String table, IndexModel index) implements SqlContributor {
    @Override
    public int priority() {
        return 60; // Index Add
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        // Reusing the existing indexStatement method from the dialect
        sb.append(dialect.indexStatement(index, table));
    }
}