package org.jinx.migration;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AlterTableBuilder {
    @Getter
    private final String tableName;
    @Getter
    private final Dialect dialect;
    private final List<SqlContributor> units = new ArrayList<>();

    public AlterTableBuilder(String tableName, Dialect dialect) {
        this.tableName = tableName;
        this.dialect = dialect;
    }

    public AlterTableBuilder add(SqlContributor unit) {
        units.add(unit);
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        units.stream()
                .sorted(Comparator.comparingInt(SqlContributor::priority))
                .forEach(c -> c.contribute(sb, dialect));
        return sb.toString().trim();
    }
}