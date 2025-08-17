package org.jinx.migration.spi.visitor;

import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.IndexModel;
import org.jinx.model.RelationshipModel;

import java.util.List;

public interface TableContentVisitor extends SqlGeneratingVisitor {
    // Column
    void visitAddedColumn(ColumnModel column);
    void visitDroppedColumn(ColumnModel column);
    void visitModifiedColumn(ColumnModel newColumn, ColumnModel oldColumn);
    void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn);

    // Primary Key
    void visitAddedPrimaryKey(List<String> pkColumns);
    void visitDroppedPrimaryKey();
    void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns);

    // Index
    void visitAddedIndex(IndexModel index);
    void visitDroppedIndex(IndexModel index);
    void visitModifiedIndex(IndexModel newIndex, IndexModel oldIndex);

    // Constraint
    void visitAddedConstraint(ConstraintModel constraint);
    void visitDroppedConstraint(ConstraintModel constraint);
    void visitModifiedConstraint(ConstraintModel newConstraint, ConstraintModel oldConstraint);

    // Relationship
    void visitAddedRelationship(RelationshipModel relationship);
    void visitDroppedRelationship(RelationshipModel relationship);
    void visitModifiedRelationship(RelationshipModel newRelationship, RelationshipModel oldRelationship);
}