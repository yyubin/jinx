package org.jinx.migration;

import lombok.Setter;
import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.PostCreateContributor;
import org.jinx.migration.contributor.TableBodyContributor;
import org.jinx.migration.contributor.create.ColumnContributor;
import org.jinx.migration.contributor.create.ConstraintContributor;
import org.jinx.migration.contributor.create.IndexContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CreateTableBuilder {
    @Setter
    private String table;
    private final DdlDialect dialect;
    private final List<DdlContributor> body = new ArrayList<>();
    private final List<DdlContributor> post = new ArrayList<>();

    public CreateTableBuilder(String table, DdlDialect d) {
        this.table = table;
        this.dialect = d;
    }

    public <T extends DdlContributor> CreateTableBuilder add(T c) {
        if (c instanceof TableBodyContributor) {
            body.add(c);
        } else if (c instanceof PostCreateContributor) {
            post.add(c);
        } else {
            throw new IllegalArgumentException("Unsupported contributor type: " + c.getClass().getName());
        }
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder(dialect.openCreateTable(table));

        body.stream()
                .sorted(Comparator.comparingInt(DdlContributor::priority))
                .forEach(c -> c.contribute(sb, dialect));

        trimTrailingComma(sb);

        sb.append(dialect.closeCreateTable()).append('\n');

        post.stream()
                .sorted(Comparator.comparingInt(DdlContributor::priority))
                .forEach(c -> c.contribute(sb, dialect));

        return sb.toString();
    }

    private void trimTrailingComma(StringBuilder sb) {
        int last = sb.lastIndexOf(",\n");
        if (last != -1) sb.delete(last, last + 2);
    }

    public CreateTableBuilder defaultsFrom(EntityModel entity) {
        var columns = entity.getColumns().values().stream().toList();
        var pkColumns = columns.stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .toList();

        // 1) 컬럼 & PK
        this.add(new ColumnContributor(pkColumns, columns));
        // 2) 제약조건
        this.add(new ConstraintContributor(entity.getConstraints().stream().toList()));
        // 3) 인덱스 (보통 CREATE TABLE 이후 생성)
        this.add(new IndexContributor(entity.getTableName(),
                entity.getIndexes().values().stream().toList()));

        return this;
    }
}
