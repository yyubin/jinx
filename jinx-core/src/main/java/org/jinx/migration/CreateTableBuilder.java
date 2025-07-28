package org.jinx.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CreateTableBuilder {
    private final Dialect dialect;
    private final List<TableBodyContributor> body = new ArrayList<>();
    private final List<PostCreateContributor> post = new ArrayList<>();

    public CreateTableBuilder(Dialect dialect) {
        this.dialect = dialect;
    }

    public CreateTableBuilder add(TableBodyContributor c) {
        body.add(c);
        return this;
    }

    public CreateTableBuilder add(PostCreateContributor c) {
        post.add(c);
        return this;
    }

    public String build(String tableName) {
        StringBuilder sb = new StringBuilder(dialect.openCreateTable(tableName));
        body.stream()
                .sorted(Comparator.comparingInt(SqlContributor::priority))
                .forEach(c -> c.contribute(sb, dialect));

        trimTrailingComma(sb);
        sb.append(dialect.closeCreateTable()).append('\n');

        post.stream()
                .sorted(Comparator.comparingInt(SqlContributor::priority))
                .forEach(c -> c.contribute(sb, dialect));

        return sb.toString();
    }

    private void trimTrailingComma(StringBuilder sb) {
        int last = sb.lastIndexOf(",\n");
        if (last != -1) {
            sb.delete(last, last + 2);
        }
    }
}