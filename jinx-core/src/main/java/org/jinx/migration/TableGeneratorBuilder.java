package org.jinx.migration;

import lombok.Getter;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.contributor.TableGeneratorContributor;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TableGeneratorBuilder {
    @Getter
    private final TableGeneratorDialect dialect;
    @Getter
    private final List<TableGeneratorContributor> units = new ArrayList<>();

    public TableGeneratorBuilder(TableGeneratorDialect dialect) {
        this.dialect = dialect;
    }

    public TableGeneratorBuilder add(TableGeneratorContributor unit) {
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
