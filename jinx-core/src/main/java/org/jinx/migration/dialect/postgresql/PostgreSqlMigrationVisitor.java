package org.jinx.migration.dialect.postgresql;

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

public class PostgreSqlMigrationVisitor extends AbstractMigrationVisitor
        implements TableVisitor, TableContentVisitor {

    private final Collection<ColumnModel> currentColumns;
    private final List<String> pkColumns;
    /**
     * 엔티티 메타데이터에서 추출한 PK 제약 이름.
     * JPA가 명시적 이름을 붙이지 않은 경우 PostgreSQL 기본 규칙({table}_pkey)으로 fallback한다.
     */
    private final String pkConstraintName;

    public PostgreSqlMigrationVisitor(DiffResult.ModifiedEntity diff, DdlDialect ddlDialect) {
        super(ddlDialect, diff);

        if (diff != null) {
            EntityModel entity = diff.getNewEntity();
            this.currentColumns = entity.getColumns().values();
            this.pkColumns = currentColumns.stream()
                    .filter(ColumnModel::isPrimaryKey)
                    .map(ColumnModel::getColumnName)
                    .toList();
            this.pkConstraintName = resolvePkConstraintName(entity);
        } else {
            this.currentColumns = List.of();
            this.pkColumns = List.of();
            this.pkConstraintName = null;
        }
    }

    public PostgreSqlMigrationVisitor(EntityModel entity, DdlDialect ddlDialect) {
        super(ddlDialect, entity);

        if (entity != null) {
            this.currentColumns = entity.getColumns().values();
            this.pkColumns = currentColumns.stream()
                    .filter(ColumnModel::isPrimaryKey)
                    .map(ColumnModel::getColumnName)
                    .toList();
            this.pkConstraintName = resolvePkConstraintName(entity);
        } else {
            this.currentColumns = List.of();
            this.pkColumns = List.of();
            this.pkConstraintName = null;
        }
    }

    /**
     * EntityModel의 constraints 맵에서 PRIMARY_KEY 제약 이름을 추출한다.
     * 명시적 이름이 없으면 PostgreSQL 기본 명명 규칙({table}_pkey)을 사용한다.
     */
    private static String resolvePkConstraintName(EntityModel entity) {
        return entity.getConstraints().values().stream()
                .filter(c -> c.getType() == ConstraintType.PRIMARY_KEY)
                .map(ConstraintModel::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(PostgreSqlUtil.defaultPkConstraintName(entity.getTableName()));
    }

    private PostgreSqlPrimaryKeyDropContributor pkDrop() {
        String name = pkConstraintName != null
                ? pkConstraintName
                : PostgreSqlUtil.defaultPkConstraintName(alterBuilder.getTableName());
        return new PostgreSqlPrimaryKeyDropContributor(alterBuilder.getTableName(), name);
    }

    @Override
    public void visitRenamedTable(DiffResult.RenamedTable renamed) {
        alterBuilder.add(new TableRenameContributor(
                renamed.getOldEntity().getTableName(),
                renamed.getNewEntity().getTableName()));
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
            alterBuilder.add(pkDrop());
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
        }
        alterBuilder.add(new ColumnModifyContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        if (oldColumn.isPrimaryKey()) {
            alterBuilder.add(pkDrop());
            alterBuilder.add(new ColumnRenameContributor(alterBuilder.getTableName(), newColumn, oldColumn));
            alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
            return;
        }
        alterBuilder.add(new ColumnRenameContributor(alterBuilder.getTableName(), newColumn, oldColumn));
    }

    @Override
    public void visitAddedPrimaryKey(List<String> columns) {
        alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
    }

    @Override
    public void visitDroppedPrimaryKey() {
        alterBuilder.add(pkDrop());
    }

    @Override
    public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        visitDroppedPrimaryKey();
        alterBuilder.add(new PrimaryKeyAddContributor(alterBuilder.getTableName(), pkColumns));
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
        alterBuilder.add(new IndexDropContributor(alterBuilder.getTableName(), oldIndex));
        alterBuilder.add(new IndexAddContributor(alterBuilder.getTableName(), newIndex));
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
        alterBuilder.add(new ConstraintDropContributor(alterBuilder.getTableName(), oldConstraint));
        alterBuilder.add(new ConstraintAddContributor(alterBuilder.getTableName(), newConstraint));
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
        alterBuilder.add(new RelationshipDropContributor(alterBuilder.getTableName(), oldRelationship));
        alterBuilder.add(new RelationshipAddContributor(alterBuilder.getTableName(), newRelationship));
    }
}
