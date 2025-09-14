package org.jinx.testing.visitor;

import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.model.SequenceModel;

public class RecordingSequenceVisitor implements SequenceVisitor {
    @Override
    public void visitAddedSequence(SequenceModel sequence) {

    }

    @Override
    public void visitDroppedSequence(SequenceModel sequence) {

    }

    @Override
    public void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence) {

    }

    @Override
    public String getGeneratedSql() {
        return "";
    }
}
