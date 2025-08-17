package org.jinx.migration.liquibase.handler;

import org.jinx.migration.liquibase.ChangeSetIdGenerator;
import org.jinx.migration.liquibase.model.*;
import org.jinx.model.DiffResult;

import java.util.ArrayList;
import java.util.List;

public class SequenceChangeSetHandler {

    public List<ChangeSetWrapper> handle(List<DiffResult.SequenceDiff> sequenceDiffs, Dialect dialect, ChangeSetIdGenerator idGenerator) {
        List<ChangeSetWrapper> changeSets = new ArrayList<>();
        for (DiffResult.SequenceDiff seqDiff : sequenceDiffs) {
            if (seqDiff.getType() == DiffResult.SequenceDiff.Type.ADDED) {
                CreateSequenceChange createSequence = CreateSequenceChange.builder()
                        .config(CreateSequenceConfig.builder()
                                .sequenceName(seqDiff.getSequence().getName())
                                .startValue(String.valueOf(seqDiff.getSequence().getInitialValue()))
                                .incrementBy(String.valueOf(seqDiff.getSequence().getAllocationSize()))
                                .build())
                        .build();
                changeSets.add(createChangeSet(idGenerator.nextId(), List.of(createSequence)));
            }
            // DROPPED 케이스도 필요 시 추가
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