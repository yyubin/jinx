package org.jinx.migration;

import org.jinx.model.EntityModel;

public interface Dialect {
    String getCreateTableSql(EntityModel entity);
    String getDropTableSql(EntityModel entity);
    String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity);
}
