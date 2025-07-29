// package org.jinx.migration.internal.drop.PrimaryKeyDropContributor;
package org.jinx.migration.internal.drop;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;

public record PrimaryKeyDropContributor(String table) implements SqlContributor {
    @Override
    public int priority() {
        return 10; // Primary Key Drop
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getDropPrimaryKeySql(table));
    }

}