package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;

import java.util.Optional;

public class IndexDiffer implements EntityComponentDiffer {
    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        newEntity.getIndexes().forEach((name, newIndex) -> {
            if (!oldEntity.getIndexes().containsKey(name)) {
                result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.ADDED)
                        .index(newIndex)
                        .build());
            } else {
                IndexModel oldIndex = oldEntity.getIndexes().get(name);
                if (!isIndexEqual(oldIndex, newIndex)) {
                    result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                            .type(DiffResult.IndexDiff.Type.MODIFIED)
                            .index(newIndex)
                            .oldIndex(oldIndex)
                            .changeDetail(getIndexChangeDetail(oldIndex, newIndex))
                            .build());
                }
            }
        });

        oldEntity.getIndexes().forEach((name, oldIndex) -> {
            if (!newEntity.getIndexes().containsKey(name)) {
                result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.DROPPED)
                        .index(oldIndex)
                        .build());
            }
        });
    }

    private boolean isIndexEqual(IndexModel oldIndex, IndexModel newIndex) {
        return Optional.ofNullable(oldIndex.getColumnNames()).equals(Optional.ofNullable(newIndex.getColumnNames()));
    }

    private String getIndexChangeDetail(IndexModel oldIndex, IndexModel newIndex) {
        StringBuilder detail = new StringBuilder();
        if (!Optional.ofNullable(oldIndex.getColumnNames()).equals(Optional.ofNullable(newIndex.getColumnNames()))) {
            detail.append("columns changed from ").append(oldIndex.getColumnNames()).append(" to ").append(newIndex.getColumnNames()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }
}