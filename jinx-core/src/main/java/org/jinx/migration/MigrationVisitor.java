package org.jinx.migration;

import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.IndexModel;
import org.jinx.model.RelationshipModel;

public interface MigrationVisitor {
    void visitAddedColumn(ColumnModel column);
    void visitDroppedColumn(ColumnModel column);
    void visitModifiedColumn(ColumnModel newColumn, ColumnModel oldColumn);
    void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn);
    void visitAddedIndex(IndexModel index);
    void visitDroppedIndex(IndexModel index);
    void visitModifiedIndex(IndexModel newIndex, IndexModel oldIndex);
    void visitAddedConstraint(ConstraintModel constraint);
    void visitDroppedConstraint(ConstraintModel constraint);
    void visitModifiedConstraint(ConstraintModel newConstraint, ConstraintModel oldConstraint);
    void visitAddedRelationship(RelationshipModel relationship);
    void visitDroppedRelationship(RelationshipModel relationship);
    void visitModifiedRelationship(RelationshipModel newRelationship, RelationshipModel oldRelationship);
}
