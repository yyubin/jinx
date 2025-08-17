package org.jinx.migration.liquibase.handler;

import org.jinx.migration.liquibase.ChangeSetIdGenerator;
import org.jinx.migration.liquibase.TableCreationResult;
import org.jinx.migration.liquibase.model.*;
import org.jinx.model.*;

import java.util.ArrayList;
import java.util.List;

public class AddedTableChangeSetHandler {

    public TableCreationResult handle(List<EntityModel> addedTables, Dialect dialect, ChangeSetIdGenerator idGenerator) {
        List<ChangeSetWrapper> createTableChangeSets = new ArrayList<>();
        List<ChangeSetWrapper> createIndexChangeSets = new ArrayList<>();
        List<ChangeSetWrapper> addFkChangeSets = new ArrayList<>();

        for (EntityModel addedTable : addedTables) {
            // 테이블 생성
            List<ColumnWrapper> columns = addedTable.getColumns().values().stream()
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
                            .tableName(addedTable.getTableName())
                            .columns(columns)
                            .build())
                    .build();

            createTableChangeSets.add(createChangeSet(idGenerator.nextId(), List.of(createTable)));

            // 인덱스 생성
            for (IndexModel index : addedTable.getIndexes().values()) {
                List<ColumnWrapper> indexColumns = index.getColumnNames().stream()
                        .map(colName -> ColumnWrapper.builder()
                                .config(ColumnConfig.builder().name(colName).build())
                                .build())
                        .toList();

                CreateIndexChange createIndex = CreateIndexChange.builder()
                        .config(CreateIndexConfig.builder()
                                .indexName(index.getIndexName())
                                .tableName(addedTable.getTableName())
                                .unique(index.isUnique())
                                .columns(indexColumns)
                                .build())
                        .build();

                createIndexChangeSets.add(createChangeSet(idGenerator.nextId(), List.of(createIndex)));
            }

            // 외래 키 생성
            for (RelationshipModel rel : addedTable.getRelationships()) {
                AddForeignKeyConstraintChange fkChange = AddForeignKeyConstraintChange.builder()
                        .config(AddForeignKeyConstraintConfig.builder()
                                .constraintName(rel.getConstraintName() != null ? rel.getConstraintName() : "fk_" + addedTable.getTableName() + "_" + rel.getColumn())
                                .baseTableName(addedTable.getTableName())
                                .baseColumnNames(rel.getColumn())
                                .referencedTableName(rel.getReferencedTable())
                                .referencedColumnNames(rel.getReferencedColumn())
                                .onDelete(rel.getOnDelete() != null ? rel.getOnDelete().name() : null)
                                .onUpdate(rel.getOnUpdate() != null ? rel.getOnUpdate().name() : null)
                                .build())
                        .build();

                addFkChangeSets.add(createChangeSet(idGenerator.nextId(), List.of(fkChange)));
            }
        }

        return new TableCreationResult(createTableChangeSets, createIndexChangeSets, addFkChangeSets);
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