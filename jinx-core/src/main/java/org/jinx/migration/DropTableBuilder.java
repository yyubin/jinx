package org.jinx.migration;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.contributor.TableContributor;
import org.jinx.migration.spi.dialect.DdlDialect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DropTableBuilder {
    private final DdlDialect dialect;
    private final List<TableContributor> contributors = new ArrayList<>();

    public DropTableBuilder(DdlDialect dialect) {
        this.dialect = dialect;
    }

    public DropTableBuilder add(TableContributor c) {
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