package org.jinx.migration.liquibase.handler;

import org.jinx.migration.liquibase.ChangeSetIdGenerator;
import org.jinx.migration.liquibase.model.*;
import org.jinx.model.EntityModel;

import java.util.ArrayList;
import java.util.List;

public class DroppedTableChangeSetHandler {

    public List<ChangeSetWrapper> handle(List<EntityModel> droppedTables, Dialect dialect, ChangeSetIdGenerator idGenerator) {
        List<ChangeSetWrapper> changeSets = new ArrayList<>();
        for (EntityModel droppedTable : droppedTables) {
            DropTableChange dropTable = DropTableChange.builder()
                    .config(DropTableConfig.builder()
                            .tableName(droppedTable.getTableName())
                            .build())
                    .build();
            changeSets.add(createChangeSet(idGenerator.nextId(), List.of(dropTable)));
        }
        return changeSets;
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