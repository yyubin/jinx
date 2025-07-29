package org.jinx.migration;

import lombok.Setter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CreateTableBuilder {
    @Setter
    private String table;
    private final Dialect dialect;
    private final List<SqlContributor> body = new ArrayList<>();
    private final List<SqlContributor> post = new ArrayList<>();

    public CreateTableBuilder(String table, Dialect d) {
        this.table = table;
        this.dialect = d;
    }

    public CreateTableBuilder add(TableBodyContributor c) {
        body.add(c);
        return this;
    }

    public CreateTableBuilder add(PostCreateContributor c) {
        post.add(c);
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder(dialect.openCreateTable(table));
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