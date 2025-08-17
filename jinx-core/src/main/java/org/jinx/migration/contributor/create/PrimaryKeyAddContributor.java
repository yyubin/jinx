// package org.jinx.migration.internal.create.PrimaryKeyAddContributor;
package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;

import java.util.List;

public record PrimaryKeyAddContributor(String table, List<String> pkColumns) implements DdlContributor {

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append(dialect.getAddPrimaryKeySql(table, pkColumns));
    }
}