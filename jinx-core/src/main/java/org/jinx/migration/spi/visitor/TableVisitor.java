package org.jinx.migration.spi.visitor;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

public interface TableVisitor extends SqlGeneratingVisitor{
    void visitAddedTable(EntityModel table);
    void visitDroppedTable(EntityModel table);
    void visitRenamedTable(DiffResult.RenamedTable renamed);
}
