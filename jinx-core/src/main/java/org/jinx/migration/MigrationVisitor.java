package org.jinx.migration;

import org.jinx.model.*;

import java.util.List;

public interface MigrationVisitor {
    // 테이블 레벨 변경
    default void visitAddedTable(EntityModel table) {};
    default void visitDroppedTable(EntityModel table) {};
    void visitRenamedTable(DiffResult.RenamedTable renamed);

    // 시퀀스 레벨 변경
    void visitAddedSequence(SequenceModel sequence);
    void visitDroppedSequence(SequenceModel sequence);
    void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence);

    // 테이블 생성자 레벨 변경
    void visitAddedTableGenerator(TableGeneratorModel tableGenerator);
    void visitDroppedTableGenerator(TableGeneratorModel tableGenerator);
    void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator);

    // ModifiedEntity 내부 변경
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
    void visitAddedPrimaryKey(List<String> pkColumns);
    void visitDroppedPrimaryKey();
    void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns);

    // 생성된 SQL 반환
    String getGeneratedSql();
}
