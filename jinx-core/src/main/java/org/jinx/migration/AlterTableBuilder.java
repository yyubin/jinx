package org.jinx.migration;

import lombok.Getter;
import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AlterTableBuilder {
    @Getter
    private final String tableName;
    @Getter
    private final DdlDialect dialect;
    @Getter
    private final List<DdlContributor> units = new ArrayList<>();

    public AlterTableBuilder(String tableName, DdlDialect dialect) {
        this.tableName = tableName;
        this.dialect = dialect;
    }

    public AlterTableBuilder add(DdlContributor unit) {
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