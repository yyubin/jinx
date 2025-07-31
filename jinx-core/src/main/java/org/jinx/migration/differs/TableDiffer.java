package org.jinx.migration.differs;

import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TableDiffer implements Differ {
    @Override
    public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        detectRenamedTables(oldSchema, newSchema, result);
        detectAddedTables(oldSchema, newSchema, result);
        detectDroppedTables(oldSchema, newSchema, result);
    }

    private void detectRenamedTables(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        Map<String, Set<String>> oldPkColumnNames = oldSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .filter(ColumnModel::isPrimaryKey)
                                .map(ColumnModel::getColumnName)
                                .collect(Collectors.toSet())
                ));

        Map<String, Set<Long>> oldColumnHashes = oldSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .map(ColumnModel::getAttributeHash)
                                .collect(Collectors.toSet())
                ));

        Map<String, Set<String>> newPkColumnNames = newSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .filter(ColumnModel::isPrimaryKey)
                                .map(ColumnModel::getColumnName)
                                .collect(Collectors.toSet())
                ));

        Map<String, Set<Long>> newColumnHashes = newSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .map(ColumnModel::getAttributeHash)
                                .collect(Collectors.toSet())
                ));

        for (String oldEntityName : oldSchema.getEntities().keySet()) {
            if (!newSchema.getEntities().containsKey(oldEntityName)) {
                EntityModel oldEntity = oldSchema.getEntities().get(oldEntityName);
                Set<String> oldPkNames = oldPkColumnNames.get(oldEntityName);
                Set<Long> oldHashes = oldColumnHashes.get(oldEntityName);

                for (String newEntityName : newSchema.getEntities().keySet()) {
                    if (!oldSchema.getEntities().containsKey(newEntityName)) {
                        EntityModel newEntity = newSchema.getEntities().get(newEntityName);
                        Set<String> newPkNames = newPkColumnNames.get(newEntityName);
                        Set<Long> newHashes = newColumnHashes.get(newEntityName);

                        if (oldPkNames.equals(newPkNames) && oldHashes.equals(newHashes)) {
                            result.getRenamedTables().add(DiffResult.RenamedTable.builder()
                                    .oldEntity(oldEntity)
                                    .newEntity(newEntity)
                                    .changeDetail("Table renamed from " + oldEntity.getTableName() + " to " + newEntity.getTableName())
                                    .build());
                        }
                    }
                }
            }
        }
    }

    private void detectAddedTables(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        newSchema.getEntities().forEach((name, newEntity) -> {
            if (!oldSchema.getEntities().containsKey(name) &&
                    !result.getRenamedTables().stream().anyMatch(rt -> rt.getNewEntity().getEntityName().equals(name))) {
                result.getAddedTables().add(newEntity);
            }
        });
    }

    private void detectDroppedTables(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        oldSchema.getEntities().forEach((name, oldEntity) -> {
            if (!newSchema.getEntities().containsKey(name) &&
                    !result.getRenamedTables().stream().anyMatch(rt -> rt.getOldEntity().getEntityName().equals(name))) {
                result.getDroppedTables().add(oldEntity);
            }
        });
    }
}