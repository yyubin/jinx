package org.jinx.migration.spi.visitor;

import org.jinx.model.TableGeneratorModel;

public interface TableGeneratorVisitor extends SqlGeneratingVisitor {
    void visitAddedTableGenerator(TableGeneratorModel tableGenerator);
    void visitDroppedTableGenerator(TableGeneratorModel tableGenerator);
    void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator);
}