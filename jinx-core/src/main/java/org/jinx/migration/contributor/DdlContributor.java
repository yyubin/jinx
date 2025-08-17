package org.jinx.migration.contributor;

import org.jinx.migration.spi.dialect.DdlDialect;

public interface DdlContributor extends SqlContributor {
    void contribute(StringBuilder sb, DdlDialect dialect);
}
