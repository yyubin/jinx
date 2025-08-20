package org.jinx.migration.liquibase;

import lombok.Getter;
import lombok.Setter;
import org.jinx.migration.liquibase.model.*;
import org.jinx.migration.spi.visitor.*;
import org.jinx.model.*;
import org.jinx.model.DiffResult.*;

import java.util.ArrayList;
import java.util.List;

@Getter
public class LiquibaseVisitor implements TableVisitor, TableContentVisitor, SequenceVisitor, TableGeneratorVisitor, SqlGeneratingVisitor {
    private final DialectBundle dialectBundle;
    private final ChangeSetIdGenerator idGenerator;
    private final List<ChangeSetWrapper> changeSets = new ArrayList<>();
    @Setter
    private String currentTableName;

    public LiquibaseVisitor(DialectBundle dialectBundle, ChangeSetIdGenerator idGenerator) {
        this.dialectBundle = dialectBundle;
        this.idGenerator = idGenerator;
    }


    // 복합 PK에서 문제 생길 수 있어서
    // 컬럼 constraints에는 primaryKey 표시를 하지 말고
    // 테이블 생성 직후 AddPrimaryKeyChange를 별도 changeSet으로 추가
    @Override
    public void visitAddedTable(EntityModel table) {
        currentTableName = table.getTableName();

        var pkCols = table.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .toList();

        List<ColumnWrapper> columns = table.getColumns().values().stream()
                .map(col -> ColumnWrapper.builder()
                        .config(ColumnConfig.builder()
                                .name(col.getColumnName())
                                .type(getLiquibaseTypeName(col))
                                .defaultValue(col.getDefaultValue()) // 필요시 타입별 defaultValueXxx로 분기 고려
                                .autoIncrement(col.getGenerationStrategy() == GenerationStrategy.IDENTITY ? true : null)
                                .constraints(LiquibaseUtils.buildConstraintsWithoutPK(col, currentTableName)) // ← PK 제외
                                .build())
                        .build())
                .toList();

        var createTable = CreateTableChange.builder()
                .config(CreateTableConfig.builder()
                        .tableName(table.getTableName())
                        .columns(columns)
                        .build())
                .build();

        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createTable)));

        if (!pkCols.isEmpty()) {
            var addPk = AddPrimaryKeyConstraintChange.builder()
                    .config(AddPrimaryKeyConstraintConfig.builder()
                            .constraintName("pk_" + currentTableName)
                            .tableName(currentTableName)
                            .columnNames(String.join(",", pkCols))
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(addPk)));
        }

        // 고유/체크 제약도 반영
        table.getConstraints().values().forEach(this::visitAddedConstraint);

        // 인덱스, FK
        table.getIndexes().values().forEach(this::visitAddedIndex);
        table.getRelationships().values().forEach(this::visitAddedRelationship);
    }


    @Override
    public void visitDroppedTable(EntityModel table) {
        DropTableChange dropTable = DropTableChange.builder()
                .config(DropTableConfig.builder()
                        .tableName(table.getTableName())
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropTable)));
    }

    @Override
    public void visitRenamedTable(RenamedTable renamed) {
        RenameTableChange renameTable = RenameTableChange.builder()
                .config(RenameTableConfig.builder()
                        .oldTableName(renamed.getOldEntity().getTableName())
                        .newTableName(renamed.getNewEntity().getTableName())
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(renameTable)));
    }

    // 시퀀스 관련 방문 메서드
    @Override
    public void visitAddedSequence(SequenceModel sequence) {
        dialectBundle.withSequence(seqDialect -> {
            CreateSequenceChange createSequence = CreateSequenceChange.builder()
                    .config(CreateSequenceConfig.builder()
                            .sequenceName(sequence.getName())
                            .startValue(String.valueOf(sequence.getInitialValue()))
                            .incrementBy(String.valueOf(sequence.getAllocationSize()))
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createSequence)));
        });
    }

    @Override
    public void visitDroppedSequence(SequenceModel sequence) {
        dialectBundle.withSequence(seqDialect -> {
            DropSequenceChange dropSequence = DropSequenceChange.builder()
                    .config(DropSequenceConfig.builder()
                            .sequenceName(sequence.getName())
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropSequence)));
        });
    }

    @Override
    public void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence) {
        dialectBundle.withSequence(seqDialect -> {
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
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropSequence)));
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createSequence)));
        });
    }

    @Override
    public void visitAddedTableGenerator(TableGeneratorModel tg) {
        dialectBundle.withTableGenerator(tgd -> {
            var createTable = CreateTableChange.builder()
                    .config(CreateTableConfig.builder()
                            .tableName(tg.getTable())
                            .columns(List.of(
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getPkColumnName())
                                            .type("varchar(255)")
                                            .constraints(Constraints.builder().primaryKey(true).build())
                                            .build()).build(),
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getValueColumnName())
                                            .type("bigint")
                                            .build()).build()
                            ))
                            .build())
                    .build();

            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createTable)));

            // 초기 row insert
            var insert = InsertDataChange.builder()
                    .config(InsertDataConfig.builder()
                            .tableName(tg.getTable())
                            .columns(List.of(
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getPkColumnName())
                                            .defaultValue(tg.getPkColumnValue()) // 필요시 valueComputed/… 고려
                                            .build()).build(),
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getValueColumnName())
                                            .defaultValue(String.valueOf(tg.getInitialValue()))
                                            .build()).build()
                            )).build())
                    .build();

            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(insert)));
        });
    }


    @Override
    public void visitDroppedTableGenerator(TableGeneratorModel tableGenerator) {
        dialectBundle.withTableGenerator(tgDialect -> {
            DropTableGeneratorChange dropTable = DropTableGeneratorChange.builder()
                    .config(DropTableConfig.builder()
                            .tableName(tableGenerator.getTable())
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropTable)));
        });
    }

    @Override
    public void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator) {
        dialectBundle.withTableGenerator(tgDialect -> {
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
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropTable)));
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createTable)));
        });
    }

    // 테이블 컨텐츠 관련 방문 메서드
    @Override
    public void visitAddedColumn(ColumnModel column) {
        AddColumnChange addColumn = AddColumnChange.builder()
                .config(AddColumnConfig.builder()
                        .tableName(currentTableName)
                        .columns(List.of(ColumnWrapper.builder()
                                .config(ColumnConfig.builder()
                                        .name(column.getColumnName())
                                        .type(getLiquibaseTypeName(column))
                                        .defaultValue(column.getDefaultValue())
                                        .autoIncrement(column.getGenerationStrategy() == GenerationStrategy.IDENTITY ? true : null)
                                        .constraints(LiquibaseUtils.buildConstraints(column, currentTableName))
                                        .build())
                                .build()))
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(addColumn)));
    }

    @Override
    public void visitDroppedColumn(ColumnModel column) {
        DropColumnChange dropColumn = DropColumnChange.builder()
                .config(DropColumnConfig.builder()
                        .tableName(currentTableName)
                        .columnName(column.getColumnName())
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropColumn)));
    }

    @Override
    public void visitModifiedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        ModifyDataTypeChange modifyDataType = ModifyDataTypeChange.builder()
                .config(ModifyDataTypeConfig.builder()
                        .tableName(currentTableName)
                        .columnName(newColumn.getColumnName())
                        .newDataType(getLiquibaseTypeName(newColumn))
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(modifyDataType)));
    }

    @Override
    public void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        RenameColumnChange renameColumn = RenameColumnChange.builder()
                .config(RenameColumnConfig.builder()
                        .tableName(currentTableName)
                        .oldColumnName(oldColumn.getColumnName())
                        .newColumnName(newColumn.getColumnName())
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(renameColumn)));
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
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createIndex)));
    }

    @Override
    public void visitDroppedIndex(IndexModel index) {
        DropIndexChange dropIndex = DropIndexChange.builder()
                .config(DropIndexConfig.builder()
                        .indexName(index.getIndexName())
                        .tableName(currentTableName)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropIndex)));
    }

    @Override
    public void visitModifiedIndex(IndexModel newIndex, IndexModel oldIndex) {
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
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropOldIndex)));
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createNewIndex)));
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
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(uniqueChange)));
        } else if (constraint.getType() == ConstraintType.CHECK) {
            AddCheckConstraintChange checkChange = AddCheckConstraintChange.builder()
                    .config(AddCheckConstraintConfig.builder()
                            .constraintName(constraint.getName() != null ? constraint.getName() : "ck_" + currentTableName)
                            .tableName(currentTableName)
                            .constraintExpression(constraint.getCheckClause().isPresent() ? constraint.getCheckClause().get() : "")
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(checkChange)));
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
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropUnique)));
        } else if (constraint.getType() == ConstraintType.CHECK) {
            DropCheckConstraintChange dropCheck = DropCheckConstraintChange.builder()
                    .config(DropCheckConstraintConfig.builder()
                            .constraintName(constraint.getName() != null ? constraint.getName() : "ck_" + currentTableName)
                            .tableName(currentTableName)
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropCheck)));
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
                        .constraintName(relationship.getConstraintName())
                        .baseTableName(currentTableName)
                        // FIX: 컬럼 이름이 여러 개일 수 있으므로, 처리해야함 이미 모델 바뀜
                        // .baseColumnNames(relationship.getColumn())
                        .referencedTableName(relationship.getReferencedTable())
                        // .referencedColumnNames(relationship.getReferencedColumn())
                        .onDelete(relationship.getOnDelete() != null ? relationship.getOnDelete().name().replace('_',' ') : null)
                        .onUpdate(relationship.getOnUpdate() != null ? relationship.getOnUpdate().name().replace('_',' ') : null)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(fkChange)));
    }

    @Override
    public void visitDroppedRelationship(RelationshipModel relationship) {
        DropForeignKeyConstraintChange dropFk = DropForeignKeyConstraintChange.builder()
                .config(DropForeignKeyConstraintConfig.builder()
                        .constraintName(relationship.getConstraintName())
                        .baseTableName(currentTableName)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropFk)));
    }

    @Override
    public void visitModifiedRelationship(RelationshipModel newRelationship, RelationshipModel oldRelationship) {
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
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(addPk)));
    }

    @Override
    public void visitDroppedPrimaryKey() {
        DropPrimaryKeyConstraintChange dropPk = DropPrimaryKeyConstraintChange.builder()
                .config(DropPrimaryKeyConstraintConfig.builder()
                        .constraintName("pk_" + currentTableName)
                        .tableName(currentTableName)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropPk)));
    }

    @Override
    public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        visitDroppedPrimaryKey();
        visitAddedPrimaryKey(newPkColumns);
    }

    @Override
    public String getGeneratedSql() {
        return ""; // Liquibase는 태그 기반이므로 SQL 직접 생성 불필요
    }

    private String getLiquibaseTypeName(ColumnModel c) {
        return dialectBundle.liquibase()
                .map(lb -> lb.getLiquibaseTypeName(c))
                .orElseGet(() -> {
                    var ddl = dialectBundle.ddl();
                    var jt = ddl.getJavaTypeMapper().map(
                            c.getConversionClass() != null ? c.getConversionClass() : c.getJavaType());
                    return jt.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
                });
    }

}