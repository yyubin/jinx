package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.PostCreateContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.IndexModel;
import java.util.List;

public record IndexContributor(String table, List<IndexModel> indexes) implements DdlContributor, PostCreateContributor {
    @Override
    public int priority() {
        return 60; // Index 생성
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        for (IndexModel idx : indexes) {
            sb.append(dialect.indexStatement(idx, table));
        }
    }
}