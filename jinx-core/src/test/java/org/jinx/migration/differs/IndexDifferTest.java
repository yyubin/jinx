package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexDifferTest {

    private IndexDiffer indexDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity result;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        indexDiffer = new IndexDiffer(CaseNormalizer.lower());
        
        oldEntity = EntityModel.builder()
                .entityName("User")
                .tableName("users")
                .indexes(new HashMap<>())
                .build();
                
        newEntity = EntityModel.builder()
                .entityName("User") 
                .tableName("users")
                .indexes(new HashMap<>())
                .build();
                
        result = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();
                
        modifiedEntityResult = result; // 기존 테스트 호환성
    }

    @Test
    @DisplayName("인덱스 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenIndexesAreIdentical() {
        IndexModel idx = createIndex("idx_username", List.of("username"));
        oldEntity.setIndexes(Map.of("idx_username", idx));
        newEntity.setIndexes(Map.of("idx_username", idx));

        indexDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getIndexDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 인덱스가 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedIndex() {
        IndexModel newIdx = createIndex("idx_email", List.of("email"));
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
        IndexModel oldIdx = createIndex("idx_username", List.of("username"));
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
        IndexModel oldIdx = createIndex("idx_user_status", List.of("status"));
        IndexModel newIdx = createIndex("idx_user_status", List.of("status", "last_login")); // columns 변경

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
    @DisplayName("대소문자만 다른 동일한 인덱스는 변경 없음으로 처리")
    void shouldIgnoreCaseOnlyDifferences() {
        IndexModel oldIndex = IndexModel.builder()
                .indexName("idx_user_name")
                .tableName("USERS")  // 대문자
                .columnNames(List.of("NAME", "EMAIL"))  // 대문자
                .build();
        
        IndexModel newIndex = IndexModel.builder()
                .indexName("idx_user_name")
                .tableName("users")  // 소문자
                .columnNames(List.of("name", "email"))  // 소문자
                .build();
        
        oldEntity.getIndexes().put("idx_user_name", oldIndex);
        newEntity.getIndexes().put("idx_user_name", newIndex);
        
        indexDiffer.diff(oldEntity, newEntity, result);
        
        // 대소문자만 다르므로 변경사항 없음
        assertTrue(result.getIndexDiffs().isEmpty());
    }

    @Test
    @DisplayName("인덱스 이름 변경 감지 (RENAME)")
    void shouldDetectIndexRename() {
        IndexModel oldIndex = IndexModel.builder()
                .indexName("idx_old_name")
                .tableName("users")
                .columnNames(List.of("email"))
                .build();
        
        IndexModel newIndex = IndexModel.builder()
                .indexName("idx_new_name")
                .tableName("users")
                .columnNames(List.of("email"))  // 구조는 동일
                .build();
        
        oldEntity.getIndexes().put("idx_old_name", oldIndex);
        newEntity.getIndexes().put("idx_new_name", newIndex);
        
        indexDiffer.diff(oldEntity, newEntity, result);
        
        assertEquals(1, result.getIndexDiffs().size());
        DiffResult.IndexDiff diff = result.getIndexDiffs().get(0);
        assertEquals(DiffResult.IndexDiff.Type.MODIFIED, diff.getType());
        assertEquals(newIndex, diff.getIndex());
        assertEquals(oldIndex, diff.getOldIndex());
        assertTrue(diff.getChangeDetail().contains("[RENAME]"));
        assertTrue(diff.getChangeDetail().contains("idx_old_name"));
        assertTrue(diff.getChangeDetail().contains("idx_new_name"));
    }

    @Test
    @DisplayName("컬럼 순서 변경은 서로 다른 인덱스로 처리")
    void shouldTreatColumnOrderChangeAsDifferentIndex() {
        IndexModel oldIndex = IndexModel.builder()
                .indexName("idx_user_composite")
                .tableName("users")
                .columnNames(List.of("name", "email"))
                .build();
        
        IndexModel newIndex = IndexModel.builder()
                .indexName("idx_user_composite")
                .tableName("users")
                .columnNames(List.of("email", "name"))  // 순서 변경
                .build();
        
        oldEntity.getIndexes().put("idx_user_composite", oldIndex);
        newEntity.getIndexes().put("idx_user_composite", newIndex);
        
        indexDiffer.diff(oldEntity, newEntity, result);
        
        assertEquals(1, result.getIndexDiffs().size());
        DiffResult.IndexDiff diff = result.getIndexDiffs().get(0);
        assertEquals(DiffResult.IndexDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("columns changed"));
    }

    @Test
    @DisplayName("복합 시나리오: 추가, 삭제, 수정, 이름변경")
    void shouldHandleComplexScenario() {
        // Old 인덱스들
        IndexModel oldIdx1 = IndexModel.builder()
                .indexName("idx_to_drop")
                .tableName("users")
                .columnNames(List.of("old_column"))
                .build();
        
        IndexModel oldIdx2 = IndexModel.builder()
                .indexName("idx_to_modify")
                .tableName("users")
                .columnNames(List.of("name"))
                .build();
        
        IndexModel oldIdx3 = IndexModel.builder()
                .indexName("idx_old_name")
                .tableName("users")
                .columnNames(List.of("email"))
                .build();
        
        // New 인덱스들
        IndexModel newIdx1 = IndexModel.builder()
                .indexName("idx_new_added")
                .tableName("users")
                .columnNames(List.of("new_column"))
                .build();
        
        IndexModel newIdx2 = IndexModel.builder()
                .indexName("idx_to_modify")
                .tableName("users")
                .columnNames(List.of("name", "email"))  // 컬럼 추가
                .build();
        
        IndexModel newIdx3 = IndexModel.builder()
                .indexName("idx_new_name")
                .tableName("users")
                .columnNames(List.of("email"))  // 구조는 동일, 이름만 변경
                .build();
        
        oldEntity.getIndexes().put("idx_to_drop", oldIdx1);
        oldEntity.getIndexes().put("idx_to_modify", oldIdx2);
        oldEntity.getIndexes().put("idx_old_name", oldIdx3);
        
        newEntity.getIndexes().put("idx_new_added", newIdx1);
        newEntity.getIndexes().put("idx_to_modify", newIdx2);
        newEntity.getIndexes().put("idx_new_name", newIdx3);
        
        indexDiffer.diff(oldEntity, newEntity, result);
        
        // 4개의 변경사항이 있어야 함: ADDED, DROPPED, MODIFIED, RENAME
        assertEquals(4, result.getIndexDiffs().size());
        
        // 추가
        assertTrue(result.getIndexDiffs().stream().anyMatch(diff ->
                diff.getType() == DiffResult.IndexDiff.Type.ADDED &&
                diff.getIndex().getIndexName().equals("idx_new_added")
        ));
        
        // 삭제
        assertTrue(result.getIndexDiffs().stream().anyMatch(diff ->
                diff.getType() == DiffResult.IndexDiff.Type.DROPPED &&
                diff.getIndex().getIndexName().equals("idx_to_drop")
        ));
        
        // 수정
        assertTrue(result.getIndexDiffs().stream().anyMatch(diff ->
                diff.getType() == DiffResult.IndexDiff.Type.MODIFIED &&
                diff.getIndex().getIndexName().equals("idx_to_modify") &&
                diff.getChangeDetail().contains("columns changed")
        ));
        
        // 이름변경 (RENAME)
        assertTrue(result.getIndexDiffs().stream().anyMatch(diff ->
                diff.getType() == DiffResult.IndexDiff.Type.MODIFIED &&
                diff.getIndex().getIndexName().equals("idx_new_name") &&
                diff.getChangeDetail().contains("[RENAME]")
        ));
    }

    @Test
    @DisplayName("null 인덱스 맵 처리")
    void shouldHandleNullIndexMaps() {
        oldEntity.setIndexes(null);
        newEntity.setIndexes(null);
        
        assertDoesNotThrow(() -> {
            indexDiffer.diff(oldEntity, newEntity, result);
        });
        
        assertTrue(result.getIndexDiffs().isEmpty());
    }

    @Test
    @DisplayName("빈 인덱스 맵 처리")
    void shouldHandleEmptyIndexMaps() {
        oldEntity.getIndexes().clear();
        newEntity.getIndexes().clear();
        
        indexDiffer.diff(oldEntity, newEntity, result);
        
        assertTrue(result.getIndexDiffs().isEmpty());
    }

    private IndexModel createIndex(String name, List<String> columns) {
        return IndexModel.builder()
                .indexName(name)
                .tableName("users")
                .columnNames(columns)
                .build();
    }
}
