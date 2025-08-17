package org.jinx.migration.contributor.alter;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;

public record TableRenameContributor(String oldTableName, String newTableName) implements DdlContributor {
    @Override
    public int priority() {
        return 10; // 테이블 이름 변경은 가장 먼저 실행
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getRenameTableSql(oldTableName, newTableName));
    }
}