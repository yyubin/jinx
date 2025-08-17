package org.jinx.migration.liquibase.handler;

import org.jinx.migration.liquibase.ChangeSetIdGenerator;
import org.jinx.migration.liquibase.model.*;
import org.jinx.model.DiffResult;

import java.util.ArrayList;
import java.util.List;

public class RenamedTableChangeSetHandler {

    public List<ChangeSetWrapper> handle(List<DiffResult.RenamedTable> renamedTables, Dialect dialect, ChangeSetIdGenerator idGenerator) {
        List<ChangeSetWrapper> changeSets = new ArrayList<>();
        for (DiffResult.RenamedTable renamedTable : renamedTables) {
            RenameTableChange renameTable = RenameTableChange.builder()
                    .config(RenameTableConfig.builder()
                            .oldTableName(renamedTable.getOldEntity().getTableName())
                            .newTableName(renamedTable.getNewEntity().getTableName())
                            .build())
                    .build();
            changeSets.add(createChangeSet(idGenerator.nextId(), List.of(renameTable)));
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