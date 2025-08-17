package org.jinx.migration.spi.dialect;

import org.jinx.model.SequenceModel;

public interface SequenceDialect extends BaseDialect {
    String getCreateSequenceSql(SequenceModel seq);
    String getDropSequenceSql(SequenceModel seq);
    String getAlterSequenceSql(SequenceModel newSeq, SequenceModel oldSeq);
}
