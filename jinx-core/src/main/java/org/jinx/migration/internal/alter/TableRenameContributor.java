package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;

public record TableRenameContributor(String oldTableName, String newTableName) implements SqlContributor {
    @Override
    public int priority() {
        return 10; // 테이블 이름 변경은 가장 먼저 실행
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getRenameTableSql(oldTableName, newTableName));
    }
}