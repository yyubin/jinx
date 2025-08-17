package org.jinx.migration.spi.dialect;

import org.jinx.model.ColumnModel;

public interface IdentityDialect extends BaseDialect {
    String getIdentityClause(ColumnModel c);
}