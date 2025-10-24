package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.PostCreateContributor;
import org.jinx.migration.contributor.TableGeneratorContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.TableGeneratorModel;

public record TableGeneratorAddContributor(TableGeneratorModel generator) implements TableGeneratorContributor {

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public void contribute(StringBuilder sb, TableGeneratorDialect dialect) {
        sb.append(dialect.getCreateTableGeneratorSql(generator));
    }

}
