package org.jinx.migration.contributor.drop;

import org.jinx.migration.contributor.TableGeneratorContributor;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.TableGeneratorModel;

public record TableGeneratorDropContributor(TableGeneratorModel table) implements TableGeneratorContributor {
    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void contribute(StringBuilder sb, TableGeneratorDialect dialect) {
        sb.append(dialect.getDropTableGeneratorSql(table));
    }
}
