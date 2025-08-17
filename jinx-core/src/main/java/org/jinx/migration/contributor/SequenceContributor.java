package org.jinx.migration.contributor;

import org.jinx.migration.spi.dialect.SequenceDialect;

public interface SequenceContributor extends SqlContributor {
    void contribute(StringBuilder sb, SequenceDialect dialect);
}
