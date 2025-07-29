package org.jinx.migration.internal.drop;

import org.jinx.migration.Dialect;
import org.jinx.migration.DropTableContributor;
import org.jinx.migration.SqlContributor;
import org.jinx.model.TableGeneratorModel;

public record TableGeneratorDropContributor(TableGeneratorModel table) implements DropTableContributor {
    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropTableGeneratorSql(table));
    }
}
