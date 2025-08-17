package org.jinx.migration.contributor;

import org.jinx.migration.spi.dialect.TableGeneratorDialect;

public interface TableGeneratorContributor extends SqlContributor{
    void contribute(StringBuilder sb, TableGeneratorDialect dialect);
}
