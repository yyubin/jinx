// package org.jinx.migration.internal.alter.PrimaryKeyAddContributor;
package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import java.util.List;

public record PrimaryKeyAddContributor(String table, List<String> pkColumns) implements SqlContributor {
    @Override
    public int priority() {
        return 90; // Primary Key Add
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getAddPrimaryKeySql(table, pkColumns));
    }
}