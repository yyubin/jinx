// package org.jinx.migration.internal.create.PrimaryKeyAddContributor;
package org.jinx.migration.internal.create;

import org.jinx.migration.Dialect;
import org.jinx.migration.SqlContributor;
import org.jinx.model.ColumnModel;

import java.util.List;
import java.util.Map;

public record PrimaryKeyAddContributor(String table, List<String> pkColumns) implements SqlContributor {

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        sb.append(dialect.getAddPrimaryKeySql(table, pkColumns));
    }
}