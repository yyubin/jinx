package org.jinx.testing.visitor;

import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.model.TableGeneratorModel;

public class RecordingTableGeneratorVisitor implements TableGeneratorVisitor {
    @Override
    public void visitAddedTableGenerator(TableGeneratorModel tableGenerator) {

    }

    @Override
    public void visitDroppedTableGenerator(TableGeneratorModel tableGenerator) {

    }

    @Override
    public void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator) {

    }

    @Override
    public String getGeneratedSql() {
        return "";
    }
}
