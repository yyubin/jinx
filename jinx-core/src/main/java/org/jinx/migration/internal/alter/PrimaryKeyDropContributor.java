// package org.jinx.migration.internal.alter.PrimaryKeyDropContributor;
package org.jinx.migration.internal.alter;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import java.util.List;

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