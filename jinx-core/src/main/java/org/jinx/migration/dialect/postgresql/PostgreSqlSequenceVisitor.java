package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.dialect.SequenceDialect;
import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.model.SequenceModel;

import java.util.StringJoiner;

public class PostgreSqlSequenceVisitor implements SequenceVisitor {

    private final SequenceDialect dialect;
    private final StringJoiner sql = new StringJoiner("\n");

    public PostgreSqlSequenceVisitor(SequenceDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public void visitAddedSequence(SequenceModel sequence) {
        sql.add(dialect.getCreateSequenceSql(sequence));
    }

    @Override
    public void visitDroppedSequence(SequenceModel sequence) {
        sql.add(dialect.getDropSequenceSql(sequence));
    }

    @Override
    public void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence) {
        String altered = dialect.getAlterSequenceSql(newSequence, oldSequence);
        if (!altered.isBlank()) sql.add(altered);
    }

    @Override
    public String getGeneratedSql() {
        return sql.toString();
    }
}
