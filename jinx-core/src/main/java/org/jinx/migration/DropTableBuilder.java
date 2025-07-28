package org.jinx.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DropTableBuilder {
    private final Dialect dialect;
    private final List<DropTableContributor> contributors = new ArrayList<>();

    public DropTableBuilder(Dialect dialect) {
        this.dialect = dialect;
    }

    public DropTableBuilder add(DropTableContributor c) {
        contributors.add(c);
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        contributors.stream()
                .sorted(Comparator.comparingInt(SqlContributor::priority))
                .forEach(c -> c.contribute(sb, dialect));
        return sb.toString();
    }
}