package org.jinx.migration.spi.dialect;

import org.jinx.model.ColumnModel;

public interface LiquibaseDialect extends BaseDialect{
    String getLiquibaseTypeName(ColumnModel column);
}
