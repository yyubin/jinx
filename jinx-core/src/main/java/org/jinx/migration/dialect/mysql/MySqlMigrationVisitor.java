package org.jinx.migration.dialect.mysql;

import lombok.Getter;
import org.jinx.migration.*;
import org.jinx.migration.internal.alter.*;
import org.jinx.migration.internal.create.*;
import org.jinx.migration.internal.drop.*;
import org.jinx.model.*;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

public class MySqlMigrationVisitor implements MigrationVisitor {
    @Getter
    private final AlterTableBuilder alterBuilder;
    private final List<String> pkColumns;
    @Getter
    private final Collection<ColumnModel> currentColumns;
    private final StringJoiner sql;
    private final Dialect dialect;

    public MySqlMigrationVisitor(DiffResult.ModifiedEntity diff, Dialect dialect) {
        this.dialect = dialect;
        this.alterBuilder = new AlterTableBuilder(diff != null ? diff.getNewEntity().getTableName() : "", dialect);
        this.sql = new StringJoiner("\n");
        if (diff != null) {
            this.pkColumns = diff.getNewEntity().getColumns().values().stream()
                    .filter(ColumnModel::isPrimaryKey)
                    .map(ColumnModel::getColumnName)
                    .toList();
            this.currentColumns = diff.getNewEntity().getColumns().values();
        } else {
            this.pkColumns = List.of();
            this.currentColumns = List.of();
        }
    }

    @Override
    public void visitRenamedTable(DiffResult.RenamedTable renamed) {
        alterBuilder.add(new TableRenameContributor(renamed.getOldEntity().getTableName(), renamed.getNewEntity().getTableName()));
    }

    @Override
    public void visitAddedSequence(SequenceModel sequence) {
        // MySQL은 시퀀스 미지원
    }

    @Override
    public void visitDroppedSequence(SequenceModel sequence) {
        // MySQL은 시퀀스 미지원
    }

    @Override
    public void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence) {
        // MySQL은 시퀀스 미지원
    }

    @Override
    public void visitAddedTableGenerator(TableGeneratorModel tableGenerator) {
        alterBuilder.add(new TableGeneratorAddContributor(tableGenerator));
    }

    @Override
    public void visitDroppedTableGenerator(TableGeneratorModel tableGenerator) {
        alterBuilder.add(new TableGeneratorDropContributor(tableGenerator));
    }

    @Override
    public void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator) {
        alterBuilder.add(new TableGeneratorModifyContributor(newTableGenerator, oldTableGenerator));
    }

    @Override
    public String getGeneratedSql() {
        String alterSql = alterBuilder.build();
        if (!alterSql.isEmpty()) {
            sql.add(alterSql);
        }
        return sql.toString();
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
            alterBuilder.add(new PrimaryKeyComplexDropContributor(alterBuilder.getTableName(), currentColumns));
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
        }
        alterBuilder.add(new ColumnModifyContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        if (oldColumn.isPrimaryKey()) {
            alterBuilder.add(new PrimaryKeyComplexDropContributor(alterBuilder.getTableName(), currentColumns));
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

    @Override
    public void visitAddedPrimaryKey(List<String> pkColumns) {
        alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
    }

    @Override
    public void visitDroppedPrimaryKey() {
        alterBuilder.add(new PrimaryKeyComplexDropContributor(alterBuilder.getTableName(), currentColumns));
    }

    @Override
    public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        visitDroppedPrimaryKey();
        alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), newPkColumns));
    }
}