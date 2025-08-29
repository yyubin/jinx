package org.jinx.migration.dialect.mysql;

import org.jinx.migration.AbstractMigrationVisitor;
import org.jinx.migration.contributor.alter.*;
import org.jinx.migration.contributor.create.*;
import org.jinx.migration.contributor.drop.*;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;
import org.jinx.model.*;

import java.util.Collection;
import java.util.List;

public class MySqlMigrationVisitor extends AbstractMigrationVisitor implements
        TableVisitor, TableContentVisitor {

    private final List<String> reOrderedPkColumns;
    private final Collection<ColumnModel> currentColumns;

    public MySqlMigrationVisitor(DiffResult.ModifiedEntity diff, DdlDialect ddlDialect) {
        super(ddlDialect, diff);

        if (diff != null) {
            this.currentColumns = diff.getNewEntity().getColumns().values();
            List<String> pkColumns = this.currentColumns.stream()
                    .filter(ColumnModel::isPrimaryKey)
                    .map(ColumnModel::getColumnName)
                    .toList();
            this.reOrderedPkColumns = MySqlUtil.reorderForIdentity(pkColumns, this.currentColumns.stream().toList());
        } else {
            this.currentColumns = List.of();
            this.reOrderedPkColumns = List.of();
        }
    }

    @Override
    public void visitRenamedTable(DiffResult.RenamedTable renamed) {
        alterBuilder.add(new TableRenameContributor(
                renamed.getOldEntity().getTableName(),
                renamed.getNewEntity().getTableName()
        ));
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
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), reOrderedPkColumns));
        }
        alterBuilder.add(new ColumnModifyContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        if (oldColumn.isPrimaryKey()) {
            alterBuilder.add(new PrimaryKeyComplexDropContributor(alterBuilder.getTableName(), currentColumns));
            alterBuilder.add(new ColumnRenameContributor(alterBuilder.getTableName(), newColumn, oldColumn));
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), reOrderedPkColumns));
            return;
        }
        alterBuilder.add(new ColumnRenameContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitAddedPrimaryKey(List<String> pkColumns) {
        alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), reOrderedPkColumns));
    }

    @Override
    public void visitDroppedPrimaryKey() {
        alterBuilder.add(new PrimaryKeyComplexDropContributor(alterBuilder.getTableName(), currentColumns));
    }

    @Override
    public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        visitDroppedPrimaryKey();
        alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), reOrderedPkColumns));
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
    public String getGeneratedSql() {
        String alterSql = alterBuilder.build();
        if (!alterSql.isEmpty()) {
            sql.add(alterSql);
        }
        return sql.toString();
    }
}
