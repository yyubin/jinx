package org.jinx.migration.contributor.alter;

import org.jinx.migration.contributor.*;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.TableGeneratorModel;

public record TableGeneratorModifyContributor(TableGeneratorModel newGen, TableGeneratorModel oldGen) implements TableGeneratorContributor {

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public void contribute(StringBuilder sb, TableGeneratorDialect dialect) {
        sb.append(dialect.getAlterTableGeneratorSql(newGen, oldGen));
    }
}
