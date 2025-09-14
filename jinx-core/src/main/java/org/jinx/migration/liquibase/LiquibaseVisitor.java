package org.jinx.migration.liquibase;

import lombok.Getter;
import lombok.Setter;
import org.jinx.migration.liquibase.model.*;
import org.jinx.migration.spi.visitor.*;
import org.jinx.model.*;
import org.jinx.model.DiffResult.*;
import org.jinx.naming.Naming;
import org.jinx.naming.DefaultNaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
public class LiquibaseVisitor implements TableVisitor, TableContentVisitor, SequenceVisitor, TableGeneratorVisitor, SqlGeneratingVisitor {
    private final DialectBundle dialectBundle;
    private final ChangeSetIdGenerator idGenerator;
    private final Naming naming;
    private final List<ChangeSetWrapper> changeSets = new ArrayList<>();
    @Setter
    private String currentTableName;

    public LiquibaseVisitor(DialectBundle dialectBundle, ChangeSetIdGenerator idGenerator) {
        this(dialectBundle, idGenerator, null);
    }

    public LiquibaseVisitor(DialectBundle dialectBundle, ChangeSetIdGenerator idGenerator, Naming naming) {
        this.dialectBundle = dialectBundle;
        this.idGenerator = idGenerator;
        this.naming = naming != null ? naming : createDefaultNaming(dialectBundle);
    }

    private DefaultNaming createDefaultNaming(DialectBundle dialectBundle) {
        int maxLength = dialectBundle.liquibase()
                .map(lb -> lb.getMaxIdentifierLength())
                .orElse(63);
        return new DefaultNaming(maxLength);
    }

    /**
     * Gets table name safely with null checks
     */
    private String getTableNameSafely(String tableName) {
        String name = tableName != null ? tableName : currentTableName;
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("Table name is not available");
        }
        return name;
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
                .map(col -> {
                    ColumnConfig.ColumnConfigBuilder builder = ColumnConfig.builder()
                            .name(col.getColumnName())
                            .type(getLiquibaseTypeName(col))
                            .autoIncrement(shouldUseAutoIncrement(col.getGenerationStrategy()))
                            .constraints(LiquibaseUtils.buildConstraintsWithoutPK(col, currentTableName)); // ← PK 제외
                    
                    // Apply priority-based default value setting
                    setDefaultValueWithPriority(builder, col);
                    
                    return ColumnWrapper.builder()
                            .config(builder.build())
                            .build();
                })
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
                            .constraintName(naming.pkName(currentTableName, pkCols))
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
            String pkType = dialectBundle.liquibase()
                    .map(lb -> lb.getTableGeneratorPkColumnType())
                    .orElse("VARCHAR(255)");
            String valueType = dialectBundle.liquibase()
                    .map(lb -> lb.getTableGeneratorValueColumnType())
                    .orElse("BIGINT");
                    
            var createTable = CreateTableChange.builder()
                    .config(CreateTableConfig.builder()
                            .tableName(tg.getTable())
                            .columns(List.of(
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getPkColumnName())
                                            .type(pkType)
                                            .constraints(Constraints.builder().primaryKey(true).build())
                                            .build()).build(),
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getValueColumnName())
                                            .type(valueType)
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
                                            .value(tg.getPkColumnValue()) // 문자열 키
                                            .build()).build(),
                                    ColumnWrapper.builder().config(ColumnConfig.builder()
                                            .name(tg.getValueColumnName())
                                            .valueNumeric(String.valueOf(tg.getInitialValue())) // 숫자값
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
            String pkType = dialectBundle.liquibase()
                    .map(lb -> lb.getTableGeneratorPkColumnType())
                    .orElse("VARCHAR(255)");
            String valueType = dialectBundle.liquibase()
                    .map(lb -> lb.getTableGeneratorValueColumnType())
                    .orElse("BIGINT");
                    
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
                                                    .name(newTableGenerator.getPkColumnName())
                                                    .type(pkType)
                                                    .constraints(Constraints.builder().primaryKey(true).build())
                                                    .build())
                                            .build(),
                                    ColumnWrapper.builder()
                                            .config(ColumnConfig.builder()
                                                    .name(newTableGenerator.getValueColumnName())
                                                    .type(valueType)
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
        ColumnConfig.ColumnConfigBuilder columnBuilder = ColumnConfig.builder()
                .name(column.getColumnName())
                .type(getLiquibaseTypeName(column))
                .autoIncrement(shouldUseAutoIncrement(column.getGenerationStrategy()))
                .constraints(LiquibaseUtils.buildConstraintsWithoutPK(column, currentTableName));
        
        // Apply priority-based default value setting
        setDefaultValueWithPriority(columnBuilder, column);
        
        AddColumnChange addColumn = AddColumnChange.builder()
                .config(AddColumnConfig.builder()
                        .tableName(currentTableName)
                        .columns(List.of(ColumnWrapper.builder()
                                .config(columnBuilder.build())
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
        List<Change> changes = new ArrayList<>();

        // (a) 타입 변경
        if (!Objects.equals(getLiquibaseTypeName(newColumn), getLiquibaseTypeName(oldColumn))) {
            ModifyDataTypeChange modifyDataType = ModifyDataTypeChange.builder()
                    .config(ModifyDataTypeConfig.builder()
                            .tableName(currentTableName)
                            .columnName(newColumn.getColumnName())
                            .newDataType(getLiquibaseTypeName(newColumn))
                            .build())
                    .build();
            changes.add(modifyDataType);
        }

        // (b) NULL 제약 변경
        Boolean oldNullable = oldColumn.isNullable();
        Boolean newNullable = newColumn.isNullable();

        if (!Objects.equals(oldNullable, newNullable)) {
            if (Boolean.FALSE.equals(newNullable)) {
                AddNotNullConstraintChange addNN = AddNotNullConstraintChange.builder()
                        .config(AddNotNullConstraintConfig.builder()
                                .tableName(currentTableName)
                                .columnName(newColumn.getColumnName())
                                .build())
                        .build();
                changes.add(addNN);
            } else if (Boolean.FALSE.equals(oldNullable) && Boolean.TRUE.equals(newNullable)) {
                // false -> true : NOT NULL 제거
                DropNotNullConstraintChange dropNN = DropNotNullConstraintChange.builder()
                        .config(DropNotNullConstraintConfig.builder()
                                .tableName(currentTableName)
                                .columnName(newColumn.getColumnName())
                                .build())
                        .build();
                changes.add(dropNN);
            }
        }

        // 변경사항이 있을 때만 ChangeSet 생성
        if (!changes.isEmpty()) {
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), changes));
        }
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
        String tableName = getTableNameSafely(index.getTableName());
        String idxName = index.getIndexName() != null
                ? index.getIndexName()
                : naming.ixName(tableName, index.getColumnNames());
        List<ColumnWrapper> indexColumns = index.getColumnNames().stream()
                .map(colName -> ColumnWrapper.builder()
                        .config(ColumnConfig.builder().name(colName).build())
                        .build())
                .toList();
        CreateIndexChange createIndex = CreateIndexChange.builder()
                .config(CreateIndexConfig.builder()
                        .indexName(idxName)
                        .tableName(tableName)
                        .columns(indexColumns)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(createIndex)));
    }

    @Override
    public void visitDroppedIndex(IndexModel index) {
        String tableName = getTableNameSafely(index.getTableName());
        String idxName = index.getIndexName() != null
                ? index.getIndexName()
                : naming.ixName(tableName, index.getColumnNames());
        DropIndexChange dropIndex = DropIndexChange.builder()
                .config(DropIndexConfig.builder()
                        .indexName(idxName)
                        .tableName(tableName)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropIndex)));
    }

    @Override
    public void visitModifiedIndex(IndexModel newIndex, IndexModel oldIndex) {
        String oldTableName = getTableNameSafely(oldIndex.getTableName());
        String newTableName = getTableNameSafely(newIndex.getTableName());
        
        String oldIdxName = oldIndex.getIndexName() != null
                ? oldIndex.getIndexName()
                : naming.ixName(oldTableName, oldIndex.getColumnNames());
        String newIdxName = newIndex.getIndexName() != null
                ? newIndex.getIndexName()
                : naming.ixName(newTableName, newIndex.getColumnNames());
        
        DropIndexChange dropOldIndex = DropIndexChange.builder()
                .config(DropIndexConfig.builder()
                        .indexName(oldIdxName)
                        .tableName(oldTableName)
                        .build())
                .build();
        CreateIndexChange createNewIndex = CreateIndexChange.builder()
                .config(CreateIndexConfig.builder()
                        .indexName(newIdxName)
                        .tableName(newTableName)
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
            String constraintName = constraint.getName() != null 
                    ? constraint.getName() 
                    : naming.uqName(currentTableName, constraint.getColumns());
            AddUniqueConstraintChange uniqueChange = AddUniqueConstraintChange.builder()
                    .config(AddUniqueConstraintConfig.builder()
                            .constraintName(constraintName)
                            .tableName(currentTableName)
                            .columnNames(String.join(",", constraint.getColumns()))
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(uniqueChange)));
        } else if (constraint.getType() == ConstraintType.CHECK) {
            String constraintName = constraint.getName() != null 
                    ? constraint.getName() 
                    : naming.ckName(currentTableName, constraint.getColumns());
            AddCheckConstraintChange checkChange = AddCheckConstraintChange.builder()
                    .config(AddCheckConstraintConfig.builder()
                            .constraintName(constraintName)
                            .tableName(currentTableName)
                            .constraintExpression(constraint.getCheckClause().orElse(""))
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(checkChange)));
        }
    }

    @Override
    public void visitDroppedConstraint(ConstraintModel constraint) {
        if (constraint.getType() == ConstraintType.UNIQUE) {
            String constraintName = constraint.getName() != null 
                    ? constraint.getName() 
                    : naming.uqName(currentTableName, constraint.getColumns());
            DropUniqueConstraintChange dropUnique = DropUniqueConstraintChange.builder()
                    .config(DropUniqueConstraintConfig.builder()
                            .constraintName(constraintName)
                            .tableName(currentTableName)
                            .build())
                    .build();
            changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropUnique)));
        } else if (constraint.getType() == ConstraintType.CHECK) {
            String constraintName = constraint.getName() != null 
                    ? constraint.getName() 
                    : naming.ckName(currentTableName, constraint.getColumns());
            DropCheckConstraintChange dropCheck = DropCheckConstraintChange.builder()
                    .config(DropCheckConstraintConfig.builder()
                            .constraintName(constraintName)
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
        if (relationship.isNoConstraint()) {
            return; // NO_CONSTRAINT인 경우 FK 생성 생략
        }
        String baseTable = getTableNameSafely(relationship.getTableName());
        String constraintName = relationship.getConstraintName() != null 
                ? relationship.getConstraintName()
                : naming.fkName(baseTable, relationship.getColumns(), relationship.getReferencedTable(), relationship.getReferencedColumns());
        AddForeignKeyConstraintChange fkChange = AddForeignKeyConstraintChange.builder()
                .config(AddForeignKeyConstraintConfig.builder()
                        .constraintName(constraintName)
                        .baseTableName(baseTable)
                        .baseColumnNames(String.join(",", relationship.getColumns()))
                        .referencedTableName(relationship.getReferencedTable())
                        .referencedColumnNames(String.join(",", relationship.getReferencedColumns()))
                        .onDelete(relationship.getOnDelete() != null ? relationship.getOnDelete().name().replace('_',' ') : null)
                        .onUpdate(relationship.getOnUpdate() != null ? relationship.getOnUpdate().name().replace('_',' ') : null)
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(fkChange)));
    }

    @Override
    public void visitDroppedRelationship(RelationshipModel relationship) {
        if (relationship.isNoConstraint()) {
            return;
        }
        String baseTable = getTableNameSafely(relationship.getTableName());
        String constraintName = relationship.getConstraintName() != null 
                ? relationship.getConstraintName()
                : naming.fkName(baseTable, relationship.getColumns(), relationship.getReferencedTable(), relationship.getReferencedColumns());
        DropForeignKeyConstraintChange dropFk = DropForeignKeyConstraintChange.builder()
                .config(DropForeignKeyConstraintConfig.builder()
                        .constraintName(constraintName)
                        .baseTableName(baseTable)
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
                        .constraintName(naming.pkName(currentTableName, pkColumns))
                        .tableName(currentTableName)
                        .columnNames(String.join(",", pkColumns))
                        .build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(addPk)));
    }

    @Override
    public void visitDroppedPrimaryKey() {
        boolean needsName = dialectBundle.liquibase()
                .map(org.jinx.migration.spi.dialect.LiquibaseDialect::pkDropNeedsName)
                .orElse(false);
        DropPrimaryKeyConstraintConfig.DropPrimaryKeyConstraintConfigBuilder cfg =
                DropPrimaryKeyConstraintConfig.builder().tableName(currentTableName);
        if (needsName) {
            cfg.constraintName(naming.pkName(currentTableName, List.of()));
        }
        DropPrimaryKeyConstraintChange dropPk = DropPrimaryKeyConstraintChange.builder()
                .config(cfg.build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropPk)));
    }

    @Override
    public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        // Drop old PK with specific column information
        boolean needsName = dialectBundle.liquibase()
                .map(org.jinx.migration.spi.dialect.LiquibaseDialect::pkDropNeedsName)
                .orElse(false);
        DropPrimaryKeyConstraintConfig.DropPrimaryKeyConstraintConfigBuilder dropCfg = 
                DropPrimaryKeyConstraintConfig.builder().tableName(currentTableName);
        if (needsName) {
            dropCfg.constraintName(naming.pkName(currentTableName, List.of())); // Use consistent empty list
        }
        DropPrimaryKeyConstraintChange dropPk = DropPrimaryKeyConstraintChange.builder()
                .config(dropCfg.build())
                .build();
        changeSets.add(LiquibaseUtils.createChangeSet(idGenerator.nextId(), List.of(dropPk)));
        
        // Add new PK
        visitAddedPrimaryKey(newPkColumns);
    }

    @Override
    public String getGeneratedSql() {
        return ""; // Liquibase는 태그 기반이므로 SQL 직접 생성 불필요
    }

    private String getLiquibaseTypeName(ColumnModel c) {
        // If sqlTypeOverride is specified, use it directly
        if (c.getSqlTypeOverride() != null && !c.getSqlTypeOverride().trim().isEmpty()) {
            return c.getSqlTypeOverride().trim();
        }
        
        return dialectBundle.liquibase()
                .map(lb -> lb.getLiquibaseTypeName(c))
                .orElseGet(() -> {
                    var ddl = dialectBundle.ddl();
                    var jt = ddl.getJavaTypeMapper().map(
                            c.getConversionClass() != null ? c.getConversionClass() : c.getJavaType());
                    return jt.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
                });
    }

    /**
     * Determines whether a column should use autoIncrement based on generation strategy
     */
    private Boolean shouldUseAutoIncrement(GenerationStrategy strategy) {
        if (strategy == null || strategy == GenerationStrategy.NONE) {
            return null;
        }
        
        return dialectBundle.liquibase()
                .map(lb -> lb.shouldUseAutoIncrement(strategy))
                .orElse(strategy == GenerationStrategy.IDENTITY); // Fallback to original logic
    }

    /**
     * Priority-based default value setting: computed > sequence > literal
     * Sets the appropriate default value based on generation strategy and column configuration.
     */
    private void setDefaultValueWithPriority(ColumnConfig.ColumnConfigBuilder builder, ColumnModel column) {
        String computedDefault = getComputedDefault(column);
        String sequenceDefault = getSequenceDefault(column);
        String literalDefault = getLiteralDefault(column);
        
        // Priority: computed > sequence > literal
        if (computedDefault != null) {
            builder.defaultValueComputed(computedDefault);
        } else if (sequenceDefault != null) {
            builder.defaultValueSequenceNext(sequenceDefault);
        } else if (literalDefault != null) {
            boolean allowLobDefault = dialectBundle.liquibase()
                    .map(org.jinx.migration.spi.dialect.LiquibaseDialect::allowLobLiteralDefault)
                    .orElse(false);
            if (!column.isLob() || allowLobDefault) {
                builder.defaultValue(literalDefault);
            }
        }
    }
    
    /**
     * Gets computed default value based on generation strategy (e.g., UUID)
     */
    private String getComputedDefault(ColumnModel column) {
        if (column.getGenerationStrategy() == GenerationStrategy.UUID) {
            return dialectBundle.liquibase()
                    .map(lb -> lb.getUuidDefaultValue())
                    .orElse(null);
        }
        return null;
    }
    
    /**
     * Gets sequence default value based on generation strategy
     */
    private String getSequenceDefault(ColumnModel column) {
        if (column.getGenerationStrategy() == GenerationStrategy.SEQUENCE) {
            // Return sequence name from defaultValue when SEQUENCE strategy is used
            return trimToNull(column.getDefaultValue());
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
    
    /**
     * Gets literal default value from column model
     */
    private String getLiteralDefault(ColumnModel column) {
        // Don't set literal default if there's a computed strategy that would override it
        if (column.getGenerationStrategy() == GenerationStrategy.UUID) {
            return null;
        }
        return column.getDefaultValue();
    }
    

}