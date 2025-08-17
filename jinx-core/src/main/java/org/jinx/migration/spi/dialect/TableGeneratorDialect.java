package org.jinx.migration.spi.dialect;

import org.jinx.model.TableGeneratorModel;

public interface TableGeneratorDialect extends BaseDialect {
    String getCreateTableGeneratorSql(TableGeneratorModel tg);
    String getDropTableGeneratorSql(TableGeneratorModel tg);
    String getAlterTableGeneratorSql(TableGeneratorModel newTg, TableGeneratorModel oldTg);
}