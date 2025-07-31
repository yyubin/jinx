package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexDifferTest {

    private IndexDiffer indexDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        indexDiffer = new IndexDiffer();
        oldEntity = new EntityModel();
        newEntity = new EntityModel();
        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();
    }

    @Test
    @DisplayName("인덱스 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenIndexesAreIdentical() {
        IndexModel idx = createIndex("idx_username", false, List.of("username"));
        oldEntity.setIndexes(Map.of("idx_username", idx));
        newEntity.setIndexes(Map.of("idx_username", idx));

        indexDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getIndexDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 인덱스가 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedIndex() {
        IndexModel newIdx = createIndex("idx_email", true, List.of("email"));
        oldEntity.setIndexes(Collections.emptyMap());
        newEntity.setIndexes(Map.of("idx_email", newIdx));

        indexDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getIndexDiffs().size());
        DiffResult.IndexDiff diff = modifiedEntityResult.getIndexDiffs().get(0);
        assertEquals(DiffResult.IndexDiff.Type.ADDED, diff.getType());
        assertEquals("idx_email", diff.getIndex().getIndexName());
    }

    @Test
    @DisplayName("기존 인덱스가 삭제되었을 때 'DROPPED'로 감지해야 함")
    void shouldDetectDroppedIndex() {
        IndexModel oldIdx = createIndex("idx_username", false, List.of("username"));
        oldEntity.setIndexes(Map.of("idx_username", oldIdx));
        newEntity.setIndexes(Collections.emptyMap());

        indexDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getIndexDiffs().size());
        DiffResult.IndexDiff diff = modifiedEntityResult.getIndexDiffs().get(0);
        assertEquals(DiffResult.IndexDiff.Type.DROPPED, diff.getType());
        assertEquals("idx_username", diff.getIndex().getIndexName());
    }

    @Test
    @DisplayName("인덱스 속성이 변경되었을 때 'MODIFIED'로 감지하고 상세 내역을 생성해야 함")
    void shouldDetectModifiedIndex_withChangeDetail() {
        IndexModel oldIdx = createIndex("idx_user_status", false, List.of("status"));
        IndexModel newIdx = createIndex("idx_user_status", false, List.of("status", "last_login")); // columns 변경

        oldEntity.setIndexes(Map.of("idx_user_status", oldIdx));
        newEntity.setIndexes(Map.of("idx_user_status", newIdx));

        indexDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getIndexDiffs().size());
        DiffResult.IndexDiff diff = modifiedEntityResult.getIndexDiffs().get(0);
        assertEquals(DiffResult.IndexDiff.Type.MODIFIED, diff.getType());
        assertEquals(oldIdx, diff.getOldIndex());
        assertEquals(newIdx, diff.getIndex());

        assertNotNull(diff.getChangeDetail());
        assertTrue(diff.getChangeDetail().contains("columns changed from [status] to [status, last_login]"));
    }

    @Test
    @DisplayName("인덱스의 unique 속성만 변경되었을 때 'MODIFIED'로 감지해야 함")
    void shouldDetectModifiedIndex_whenUniquenessChanges() {
        IndexModel oldIdx = createIndex("idx_email", false, List.of("email"));
        IndexModel newIdx = createIndex("idx_email", true, List.of("email")); // isUnique 변경

        oldEntity.setIndexes(Map.of("idx_email", oldIdx));
        newEntity.setIndexes(Map.of("idx_email", newIdx));

        indexDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getIndexDiffs().size());
        DiffResult.IndexDiff diff = modifiedEntityResult.getIndexDiffs().get(0);
        assertEquals(DiffResult.IndexDiff.Type.MODIFIED, diff.getType());

        assertNotNull(diff.getChangeDetail());
        assertTrue(diff.getChangeDetail().contains("isUnique changed from false to true"));
    }

    private IndexModel createIndex(String name, boolean isUnique, List<String> columns) {
        IndexModel index = IndexModel.builder().build();
        index.setIndexName(name);
        index.setUnique(isUnique);
        index.setColumnNames(columns);
        return index;
    }
}
