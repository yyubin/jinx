package org.jinx.migration.internal.create;

import org.jinx.migration.*;
import org.jinx.model.ColumnModel;
import java.util.List;
import java.util.Map;

public record ColumnContributor(List<String> pkColumns, List<ColumnModel> columns) implements TableBodyContributor {
    @Override
    public int priority() {
        return 40; // Column 정의
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        for (ColumnModel c : columns) {
            // 컬럼 정의 SQL 생성을 Dialect에 위임합니다.
            sb.append("  ").append(dialect.getColumnDefinitionSql(c)).append(",\n");
        }
        if (pkColumns != null && !pkColumns.isEmpty()) {
            // PK 정의 SQL 생성을 Dialect에 위임합니다.
            sb.append("  ").append(dialect.getPrimaryKeyDefinitionSql(pkColumns)).append(",\n");
        }
        sb.setLength(sb.length() - 2); // 마지막 ",\n" 제거
        sb.append('\n');
    }
}