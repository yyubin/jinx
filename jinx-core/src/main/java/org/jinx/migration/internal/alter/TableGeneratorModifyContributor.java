package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.TableGeneratorModel;

public record TableGeneratorModifyContributor(TableGeneratorModel newGen, TableGeneratorModel oldGen) implements SqlContributor {

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getAlterTableGeneratorSql(newGen, oldGen));
    }
}
