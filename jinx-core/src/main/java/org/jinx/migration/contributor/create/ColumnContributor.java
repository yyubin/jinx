package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.contributor.TableBodyContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;
import java.util.List;

public record ColumnContributor(List<String> pkColumns, List<ColumnModel> columns) implements DdlContributor, TableBodyContributor {
    @Override
    public int priority() {
        return 40; // Column 정의
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        for (ColumnModel c : columns) {
            // 컬럼 정의 SQL 생성을 Dialect에 위임
            sb.append("  ").append(dialect.getColumnDefinitionSql(c)).append(",\n");
        }
        if (pkColumns != null && !pkColumns.isEmpty()) {
            // PK 정의 SQL 생성을 Dialect에 위임
            sb.append("  ").append(dialect.getPrimaryKeyDefinitionSql(pkColumns)).append(",\n");
        }
    }
}