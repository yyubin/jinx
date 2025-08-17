package org.jinx.migration.spi.visitor;

import org.jinx.model.SequenceModel;

public interface SequenceVisitor extends SqlGeneratingVisitor {
    void visitAddedSequence(SequenceModel sequence);
    void visitDroppedSequence(SequenceModel sequence);
    void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence);
}