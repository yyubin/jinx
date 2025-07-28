package org.jinx.migration.dialect.mysql;

import lombok.Getter;
import org.jinx.migration.*;
import org.jinx.migration.internal.alter.*;
import org.jinx.model.*;

import java.util.List;

public class MySqlMigrationVisitor implements MigrationVisitor {
    @Getter
    private final AlterTableBuilder alterBuilder;
    private final List<String> pkColumns;


    // Dialect를 직접 받는 생성자로 로직을 통일합니다.
    public MySqlMigrationVisitor(DiffResult.ModifiedEntity diff, Dialect dialect) {
        this.alterBuilder = new AlterTableBuilder(diff.getNewEntity().getTableName(), dialect);
        this.pkColumns = diff.getNewEntity().getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .toList();
    }

    @Override
    public void visitAddedColumn(ColumnModel column) {
        alterBuilder.add(new ColumnAddContributor(alterBuilder.getTableName(), column));
    }

    @Override
    public void visitDroppedColumn(ColumnModel column) {
        alterBuilder.add(new ColumnDropContributor(alterBuilder.getTableName(), column));
    }

    @Override
    public void visitModifiedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        if (oldColumn.isPrimaryKey() || newColumn.isPrimaryKey()) {
            alterBuilder.add(new PrimaryKeyDropContributor(alterBuilder.getTableName()));
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
        }
        alterBuilder.add(new ColumnModifyContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        if (oldColumn.isPrimaryKey()) {
            alterBuilder.add(new PrimaryKeyDropContributor(alterBuilder.getTableName()));
            alterBuilder.add(new ColumnRenameContributor(alterBuilder.getTableName(), newColumn, oldColumn));
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
            return;
        }
        alterBuilder.add(new ColumnRenameContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitAddedIndex(IndexModel index) {
        alterBuilder.add(new IndexAddContributor(alterBuilder.getTableName(), index));
    }

    @Override
    public void visitDroppedIndex(IndexModel index) {
        alterBuilder.add(new IndexDropContributor(alterBuilder.getTableName(), index));
    }

    @Override
    public void visitModifiedIndex(IndexModel newIndex, IndexModel oldIndex) {
        alterBuilder.add(new IndexModifyContributor(alterBuilder.getTableName(), newIndex, oldIndex));
    }

    @Override
    public void visitAddedConstraint(ConstraintModel constraint) {
        alterBuilder.add(new ConstraintAddContributor(alterBuilder.getTableName(), constraint));
    }

    @Override
    public void visitDroppedConstraint(ConstraintModel constraint) {
        alterBuilder.add(new ConstraintDropContributor(alterBuilder.getTableName(), constraint));
    }

    @Override
    public void visitModifiedConstraint(ConstraintModel newConstraint, ConstraintModel oldConstraint) {
        alterBuilder.add(new ConstraintModifyContributor(alterBuilder.getTableName(), newConstraint, oldConstraint));
    }

    @Override
    public void visitAddedRelationship(RelationshipModel relationship) {
        alterBuilder.add(new RelationshipAddContributor(alterBuilder.getTableName(), relationship));
    }

    @Override
    public void visitDroppedRelationship(RelationshipModel relationship) {
        alterBuilder.add(new RelationshipDropContributor(alterBuilder.getTableName(), relationship));
    }

    @Override
    public void visitModifiedRelationship(RelationshipModel newRelationship, RelationshipModel oldRelationship) {
        alterBuilder.add(new RelationshipModifyContributor(alterBuilder.getTableName(), newRelationship, oldRelationship));
    }
}