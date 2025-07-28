package org.jinx.migration.internal.create;

import org.jinx.migration.*;
import org.jinx.model.IndexModel;
import java.util.List;

public record IndexContributor(String table, List<IndexModel> indexes) implements PostCreateContributor {
    @Override
    public int priority() {
        return 60; // Index 생성
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        for (IndexModel idx : indexes) {
            sb.append(dialect.indexStatement(idx, table));
        }
    }
}