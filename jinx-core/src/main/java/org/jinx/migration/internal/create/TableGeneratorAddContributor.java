package org.jinx.migration.internal.create;

import org.jinx.migration.Dialect;
import org.jinx.migration.PostCreateContributor;
import org.jinx.migration.SqlContributor;
import org.jinx.model.TableGeneratorModel;

public record TableGeneratorAddContributor(TableGeneratorModel generator) implements PostCreateContributor {

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getCreateTableGeneratorSql(generator));
    }
}
