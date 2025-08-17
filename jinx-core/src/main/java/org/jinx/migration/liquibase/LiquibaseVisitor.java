package org.jinx.migration.liquibase;

import lombok.Getter;
import lombok.Setter;
import org.jinx.migration.MigrationVisitor;
import org.jinx.migration.liquibase.model.*;
import org.jinx.model.*;

import java.util.ArrayList;
import java.util.List;

@Getter
public class LiquibaseVisitor implements MigrationVisitor {
    private final Dialect dialect;
    private final ChangeSetIdGenerator idGenerator;
    private final List<ChangeSetWrapper> sequenceChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> tableChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> columnChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> createdIndexChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> droppedIndexChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> createdFkChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> droppedFkChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> constraintChanges = new ArrayList<>();
    private final List<ChangeSetWrapper> primaryKeyChanges = new ArrayList<>();
    @Setter
    private String currentTableName;

    public LiquibaseVisitor(Dialect dialect, ChangeSetIdGenerator idGenerator) {
        this.dialect = dialect;
        this.idGenerator = idGenerator;
    }

    @Override
    public void visitAddedTable(EntityModel table) {
        currentTableName = table.getTableName();
        List<ColumnWrapper> columns = table.getColumns().values().stream()
                .map(col -> ColumnWrapper.builder()
                        .config(ColumnConfig.builder()
                                .name(col.getColumnName())
                                .type(dialect.getLiquibaseTypeName(col))
                                .defaultValue(col.getDefaultValue())
                                .defaultValueSequenceNext(col.getGenerationStrategy() == GenerationStrategy.SEQUENCE ? col.getSequenceName() : null)
                                .autoIncrement(col.getGenerationStrategy() == GenerationStrategy.IDENTITY ? true : null)
                                .constraints(buildConstraints(col))
                                .build())
                        .build())
                .toList();

        CreateTableChange createTable = CreateTableChange.builder()
                .config(CreateTableConfig.builder()
                        .tableName(table.getTableName())
                        .columns(columns)
                        .build())
                .build();
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(createTable)));

        // 인덱스 생성
        for (IndexModel index : table.getIndexes().values()) {
            visitAddedIndex(index);
        }

        // 외래 키 생성
        for (RelationshipModel rel : table.getRelationships()) {
            visitAddedRelationship(rel);
        }
    }

    @Override
    public void visitDroppedTable(EntityModel table) {
        DropTableChange dropTable = DropTableChange.builder()
                .config(DropTableConfig.builder()
                        .tableName(table.getTableName())
                        .build())
                .build();
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropTable)));
    }

    @Override
    public void visitRenamedTable(DiffResult.RenamedTable renamed) {
        RenameTableChange renameTable = RenameTableChange.builder()
                .config(RenameTableConfig.builder()
                        .oldTableName(renamed.getOldEntity().getTableName())
                        .newTableName(renamed.getNewEntity().getTableName())
                        .build())
                .build();
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(renameTable)));
    }

    @Override
    public void visitAddedSequence(SequenceModel sequence) {
        CreateSequenceChange createSequence = CreateSequenceChange.builder()
                .config(CreateSequenceConfig.builder()
                        .sequenceName(sequence.getName())
                        .startValue(String.valueOf(sequence.getInitialValue()))
                        .incrementBy(String.valueOf(sequence.getAllocationSize()))
                        .build())
                .build();
        sequenceChanges.add(createChangeSet(idGenerator.nextId(), List.of(createSequence)));
    }

    @Override
    public void visitDroppedSequence(SequenceModel sequence) {
        DropSequenceChange dropSequence = DropSequenceChange.builder()
                .config(DropSequenceConfig.builder()
                        .sequenceName(sequence.getName())
                        .build())
                .build();
        sequenceChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropSequence)));
    }

    @Override
    public void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence) {
        DropSequenceChange dropSequence = DropSequenceChange.builder()
                .config(DropSequenceConfig.builder()
                        .sequenceName(oldSequence.getName())
                        .build())
                .build();
        CreateSequenceChange createSequence = CreateSequenceChange.builder()
                .config(CreateSequenceConfig.builder()
                        .sequenceName(newSequence.getName())
                        .startValue(String.valueOf(newSequence.getInitialValue()))
                        .incrementBy(String.valueOf(newSequence.getAllocationSize()))
                        .build())
                .build();
        sequenceChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropSequence)));
        sequenceChanges.add(createChangeSet(idGenerator.nextId(), List.of(createSequence)));
    }

    @Override
    public void visitAddedTableGenerator(TableGeneratorModel tableGenerator) {
        CreateTableGeneratorChange createTable = CreateTableGeneratorChange.builder()
                .config(CreateTableConfig.builder()
                        .tableName(tableGenerator.getName())
                        .columns(List.of(
                                ColumnWrapper.builder()
                                        .config(ColumnConfig.builder()
                                                .name("pk_column")
                                                .type("varchar(255)")
                                                .constraints(Constraints.builder().primaryKey(true).build())
                                                .build())
                                        .build(),
                                ColumnWrapper.builder()
                                        .config(ColumnConfig.builder()
                                                .name("value_column")
                                                .type("bigint")
                                                .build())
                                        .build()))
                        .build())
                .build();
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(createTable)));
    }

    @Override
    public void visitDroppedTableGenerator(TableGeneratorModel tableGenerator) {
        DropTableGeneratorChange dropTable = DropTableGeneratorChange.builder()
                .config(DropTableConfig.builder()
                        .tableName(tableGenerator.getTable())
                        .build())
                .build();
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropTable)));
    }

    @Override
    public void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator) {
        DropTableGeneratorChange dropTable = DropTableGeneratorChange.builder()
                .config(DropTableConfig.builder()
                        .tableName(oldTableGenerator.getTable())
                        .build())
                .build();
        CreateTableGeneratorChange createTable = CreateTableGeneratorChange.builder()
                .config(CreateTableConfig.builder()
                        .tableName(newTableGenerator.getTable())
                        .columns(List.of(
                                ColumnWrapper.builder()
                                        .config(ColumnConfig.builder()
                                                .name("pk_column")
                                                .type("varchar(255)")
                                                .constraints(Constraints.builder().primaryKey(true).build())
                                                .build())
                                        .build(),
                                ColumnWrapper.builder()
                                        .config(ColumnConfig.builder()
                                                .name("value_column")
                                                .type("bigint")
                                                .build())
                                        .build()))
                        .build())
                .build();
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropTable)));
        tableChanges.add(createChangeSet(idGenerator.nextId(), List.of(createTable)));
    }

    @Override
    public void visitAddedColumn(ColumnModel column) {
        AddColumnChange addColumn = AddColumnChange.builder()
                .config(AddColumnConfig.builder()
                        .tableName(currentTableName)
                        .columns(List.of(ColumnWrapper.builder()
                                .config(ColumnConfig.builder()
                                        .name(column.getColumnName())
                                        .type(dialect.getLiquibaseTypeName(column))
                                        .defaultValue(column.getDefaultValue())
                                        .autoIncrement(column.getGenerationStrategy() == GenerationStrategy.IDENTITY ? true : null)
                                        .constraints(buildConstraints(column))
                                        .build())
                                .build()))
                        .build())
                .build();
        columnChanges.add(createChangeSet(idGenerator.nextId(), List.of(addColumn)));
    }

    @Override
    public void visitDroppedColumn(ColumnModel column) {
        DropColumnChange dropColumn = DropColumnChange.builder()
                .config(DropColumnConfig.builder()
                        .tableName(currentTableName)
                        .columnName(column.getColumnName())
                        .build())
                .build();
        columnChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropColumn)));
    }

    @Override
    public void visitModifiedColumn(ColumnModel oldColumn, ColumnModel newColumn) {
        ModifyDataTypeChange modifyDataType = ModifyDataTypeChange.builder()
                .config(ModifyDataTypeConfig.builder()
                        .tableName(currentTableName)
                        .columnName(newColumn.getColumnName())
                        .newDataType(dialect.getLiquibaseTypeName(newColumn))
                        .build())
                .build();
        columnChanges.add(createChangeSet(idGenerator.nextId(), List.of(modifyDataType)));
    }

    @Override
    public void visitRenamedColumn(ColumnModel oldColumn, ColumnModel newColumn) {
        RenameColumnChange renameColumn = RenameColumnChange.builder()
                .config(RenameColumnConfig.builder()
                        .tableName(currentTableName)
                        .oldColumnName(oldColumn.getColumnName())
                        .newColumnName(newColumn.getColumnName())
                        .build())
                .build();
        columnChanges.add(createChangeSet(idGenerator.nextId(), List.of(renameColumn)));
    }

    @Override
    public void visitAddedIndex(IndexModel index) {
        List<ColumnWrapper> indexColumns = index.getColumnNames().stream()
                .map(colName -> ColumnWrapper.builder()
                        .config(ColumnConfig.builder().name(colName).build())
                        .build())
                .toList();
        CreateIndexChange createIndex = CreateIndexChange.builder()
                .config(CreateIndexConfig.builder()
                        .indexName(index.getIndexName())
                        .tableName(currentTableName)
                        .unique(index.isUnique())
                        .columns(indexColumns)
                        .build())
                .build();
        createdIndexChanges.add(createChangeSet(idGenerator.nextId(), List.of(createIndex)));
    }

    @Override
    public void visitDroppedIndex(IndexModel index) {
        DropIndexChange dropIndex = DropIndexChange.builder()
                .config(DropIndexConfig.builder()
                        .indexName(index.getIndexName())
                        .tableName(currentTableName)
                        .build())
                .build();
        droppedIndexChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropIndex)));
    }

    @Override
    public void visitModifiedIndex(IndexModel oldIndex, IndexModel newIndex) {
        DropIndexChange dropOldIndex = DropIndexChange.builder()
                .config(DropIndexConfig.builder()
                        .indexName(oldIndex.getIndexName())
                        .tableName(currentTableName)
                        .build())
                .build();
        CreateIndexChange createNewIndex = CreateIndexChange.builder()
                .config(CreateIndexConfig.builder()
                        .indexName(newIndex.getIndexName())
                        .tableName(currentTableName)
                        .unique(newIndex.isUnique())
                        .columns(newIndex.getColumnNames().stream()
                                .map(colName -> ColumnWrapper.builder()
                                        .config(ColumnConfig.builder().name(colName).build())
                                        .build())
                                .toList())
                        .build())
                .build();
        droppedIndexChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropOldIndex)));
        createdIndexChanges.add(createChangeSet(idGenerator.nextId(), List.of(createNewIndex)));
    }

    @Override
    public void visitAddedConstraint(ConstraintModel constraint) {
        if (constraint.getType() == ConstraintType.UNIQUE) {
            AddUniqueConstraintChange uniqueChange = AddUniqueConstraintChange.builder()
                    .config(AddUniqueConstraintConfig.builder()
                            .constraintName(constraint.getName() != null ? constraint.getName() : "uk_" + currentTableName + "_" + String.join("_", constraint.getColumns()))
                            .tableName(currentTableName)
                            .columnNames(String.join(",", constraint.getColumns()))
                            .build())
                    .build();
            constraintChanges.add(createChangeSet(idGenerator.nextId(), List.of(uniqueChange)));
        } else if (constraint.getType() == ConstraintType.CHECK) {
            AddCheckConstraintChange checkChange = AddCheckConstraintChange.builder()
                    .config(AddCheckConstraintConfig.builder()
                            .constraintName(constraint.getName() != null ? constraint.getName() : "ck_" + currentTableName)
                            .tableName(currentTableName)
                            .constraintExpression(constraint.getCheckClause())
                            .build())
                    .build();
            constraintChanges.add(createChangeSet(idGenerator.nextId(), List.of(checkChange)));
        }
    }

    @Override
    public void visitDroppedConstraint(ConstraintModel constraint) {
        if (constraint.getType() == ConstraintType.UNIQUE) {
            DropUniqueConstraintChange dropUnique = DropUniqueConstraintChange.builder()
                    .config(DropUniqueConstraintConfig.builder()
                            .constraintName(constraint.getName() != null ? constraint.getName() : "uk_" + currentTableName + "_" + String.join("_", constraint.getColumns()))
                            .tableName(currentTableName)
                            .build())
                    .build();
            constraintChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropUnique)));
        } else if (constraint.getType() == ConstraintType.CHECK) {
            DropCheckConstraintChange dropCheck = DropCheckConstraintChange.builder()
                    .config(DropCheckConstraintConfig.builder()
                            .constraintName(constraint.getName() != null ? constraint.getName() : "ck_" + currentTableName)
                            .tableName(currentTableName)
                            .build())
                    .build();
            constraintChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropCheck)));
        }
    }

    @Override
    public void visitModifiedConstraint(ConstraintModel newConstraint, ConstraintModel oldConstraint) {
        visitDroppedConstraint(oldConstraint);
        visitAddedConstraint(newConstraint);
    }

    @Override
    public void visitAddedRelationship(RelationshipModel relationship) {
        AddForeignKeyConstraintChange fkChange = AddForeignKeyConstraintChange.builder()
                .config(AddForeignKeyConstraintConfig.builder()
                        .constraintName(relationship.getConstraintName() != null ? relationship.getConstraintName() : "fk_" + currentTableName + "_" + relationship.getColumn())
                        .baseTableName(currentTableName)
                        .baseColumnNames(relationship.getColumn())
                        .referencedTableName(relationship.getReferencedTable())
                        .referencedColumnNames(relationship.getReferencedColumn())
                        .onDelete(relationship.getOnDelete() != null ? relationship.getOnDelete().name() : null)
                        .onUpdate(relationship.getOnUpdate() != null ? relationship.getOnUpdate().name() : null)
                        .build())
                .build();
        createdFkChanges.add(createChangeSet(idGenerator.nextId(), List.of(fkChange)));
    }

    @Override
    public void visitDroppedRelationship(RelationshipModel relationship) {
        DropForeignKeyConstraintChange dropFk = DropForeignKeyConstraintChange.builder()
                .config(DropForeignKeyConstraintConfig.builder()
                        .constraintName(relationship.getConstraintName() != null ? relationship.getConstraintName() : "fk_" + currentTableName + "_" + relationship.getColumn())
                        .baseTableName(currentTableName)
                        .build())
                .build();
        droppedFkChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropFk)));
    }

    @Override
    public void visitModifiedRelationship(RelationshipModel oldRelationship, RelationshipModel newRelationship) {
        visitDroppedRelationship(oldRelationship);
        visitAddedRelationship(newRelationship);
    }

    @Override
    public void visitAddedPrimaryKey(List<String> pkColumns) {
        AddPrimaryKeyConstraintChange addPk = AddPrimaryKeyConstraintChange.builder()
                .config(AddPrimaryKeyConstraintConfig.builder()
                        .constraintName("pk_" + currentTableName)
                        .tableName(currentTableName)
                        .columnNames(String.join(",", pkColumns))
                        .build())
                .build();
        primaryKeyChanges.add(createChangeSet(idGenerator.nextId(), List.of(addPk)));
    }

    @Override
    public void visitDroppedPrimaryKey() {
        DropPrimaryKeyConstraintChange dropPk = DropPrimaryKeyConstraintChange.builder()
                .config(DropPrimaryKeyConstraintConfig.builder()
                        .constraintName("pk_" + currentTableName)
                        .tableName(currentTableName)
                        .build())
                .build();
        primaryKeyChanges.add(createChangeSet(idGenerator.nextId(), List.of(dropPk)));
    }

    @Override
    public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        visitDroppedPrimaryKey();
        visitAddedPrimaryKey(newPkColumns);
    }

    @Override
    public String getGeneratedSql() {
        return ""; // Liquibase 태그 기반으로 작동하므로 SQL 직접 생성 불필요
    }

    public List<ChangeSetWrapper> getFinalChangeSets() {
        List<ChangeSetWrapper> finalChangeSets = new ArrayList<>();
        finalChangeSets.addAll(sequenceChanges); // 시퀀스 먼저
        finalChangeSets.addAll(tableChanges.stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof DropTableChange || change instanceof DropTableGeneratorChange))
                .toList()); // 테이블 삭제
        finalChangeSets.addAll(droppedFkChanges); // 외래 키 삭제
        finalChangeSets.addAll(constraintChanges.stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof DropUniqueConstraintChange || change instanceof DropCheckConstraintChange))
                .toList()); // 제약조건 삭제
        finalChangeSets.addAll(droppedIndexChanges); // 인덱스 삭제
        finalChangeSets.addAll(tableChanges.stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof RenameTableChange))
                .toList()); // 테이블 이름 변경
        finalChangeSets.addAll(tableChanges.stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof CreateTableChange || change instanceof CreateTableGeneratorChange))
                .toList()); // 테이블 생성
        finalChangeSets.addAll(columnChanges); // 컬럼 변경
        finalChangeSets.addAll(primaryKeyChanges); // 기본 키 변경
        finalChangeSets.addAll(createdIndexChanges); // 인덱스 생성
        finalChangeSets.addAll(constraintChanges.stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof AddUniqueConstraintChange || change instanceof AddCheckConstraintChange))
                .toList()); // 제약조건 생성
        finalChangeSets.addAll(createdFkChanges); // 외래 키 생성
        return finalChangeSets;
    }

    private Constraints buildConstraints(ColumnModel col) {
        return Constraints.builder()
                .primaryKey(col.isPrimaryKey() || col.isManualPrimaryKey() ? true : null)
                .primaryKeyName(col.isPrimaryKey() ? "pk_" + col.getTableName() + "_" + col.getColumnName() : null)
                .nullable(col.isNullable() ? null : false)
                .unique(col.isUnique() ? true : null)
                .uniqueConstraintName(col.isUnique() ? "uk_" + col.getTableName() + "_" + col.getColumnName() : null)
                .build();
    }

    private ChangeSetWrapper createChangeSet(String id, List<Object> changes) {
        return ChangeSetWrapper.builder()
                .changeSet(ChangeSet.builder()
                        .id(id)
                        .author("auto-generated")
                        .changes(changes)
                        .build())
                .build();
    }
}