package org.jinx.migration.contributor;

import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.visitor.TableVisitor;

public interface TableContributor extends SqlContributor{
    void contribute(StringBuilder sb, DdlDialect dialect);
}
