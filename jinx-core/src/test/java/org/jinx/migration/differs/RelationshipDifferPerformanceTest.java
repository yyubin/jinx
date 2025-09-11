package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipDifferPerformanceTest {

    private RelationshipDiffer relationshipDiffer;
    private CaseNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = CaseNormalizer.lower();
        relationshipDiffer = new RelationshipDiffer(normalizer);
    }

    @Test
    @DisplayName("NormalizedRel 최적화가 정확성을 유지하는지 검증")
    void normalizedRelOptimizationShouldMaintainCorrectness() {
        // oldEntity는 빈 관계, newEntity는 50개 관계 (모두 ADDED로 감지되어야 함)
        EntityModel oldEntity = EntityModel.builder()
                .entityName("OldEntity")
                .relationships(new HashMap<>())
                .build();
        EntityModel newEntity = createEntityWithManyRelationships("NewEntity", 50);
        
        DiffResult.ModifiedEntity result = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        // diff 실행 - NPE나 예외 없이 완료되어야 함
        assertDoesNotThrow(() -> {
            relationshipDiffer.diff(oldEntity, newEntity, result);
        });

        // 50개의 새로운 관계가 추가되어야 함
        assertEquals(50, result.getRelationshipDiffs().size());
        
        // 모든 diff가 ADDED 타입이어야 함
        assertTrue(result.getRelationshipDiffs().stream()
                .allMatch(diff -> diff.getType() == DiffResult.RelationshipDiff.Type.ADDED));
    }

    @Test
    @DisplayName("NormalizedRel이 동일한 관계를 올바르게 인식하는지 검증")
    void normalizedRelShouldCorrectlyIdentifyIdenticalRelationships() {
        // 대소문자만 다른 동일한 관계들 생성
        RelationshipModel rel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("USER")  // 대문자
                .columns(List.of("TEAM_ID"))  // 대문자
                .referencedTable("TEAM")  // 대문자
                .referencedColumns(List.of("ID"))  // 대문자
                .constraintName("FK_USER_TEAM")  // 대문자
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("user")  // 소문자
                .columns(List.of("team_id"))  // 소문자
                .referencedTable("team")  // 소문자
                .referencedColumns(List.of("id"))  // 소문자
                .constraintName("fk_user_team")  // 소문자
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        Map<String, RelationshipModel> oldRels = Map.of("team", rel1);
        Map<String, RelationshipModel> newRels = Map.of("team", rel2);

        EntityModel oldEntity = EntityModel.builder()
                .entityName("User")
                .relationships(new HashMap<>(oldRels))
                .build();
        EntityModel newEntity = EntityModel.builder()
                .entityName("User")
                .relationships(new HashMap<>(newRels))
                .build();

        DiffResult.ModifiedEntity result = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        relationshipDiffer.diff(oldEntity, newEntity, result);

        // 대소문자만 다른 관계는 동일하다고 인식되어야 하므로 변경사항 없음
        assertTrue(result.getRelationshipDiffs().isEmpty(), 
            "대소문자만 다른 관계는 동일하다고 인식되어야 함");
    }

    @Test
    @DisplayName("NormalizedRel 캐싱이 성능에 도움되는지 기본 검증")
    void normalizedRelCachingShouldImprovePerformance() {
        // oldEntity는 빈 관계, newEntity는 100개 복잡한 관계
        EntityModel oldEntity = EntityModel.builder()
                .entityName("OldEntity")
                .relationships(new HashMap<>())
                .build();
        EntityModel newEntity = createComplexEntity("NewEntity", 100);
        
        DiffResult.ModifiedEntity result = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        // 시간 측정 - 단순히 완료 시간이 합리적인지 확인
        long startTime = System.nanoTime();
        
        relationshipDiffer.diff(oldEntity, newEntity, result);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        // 100개 관계 처리가 1초 미만이어야 함 (성능 기준선)
        assertTrue(durationMs < 1000, 
            "100개 관계 처리가 " + durationMs + "ms 소요됨. 1000ms 미만이어야 함");

        // 100개의 새로운 관계가 추가되어야 함
        assertEquals(100, result.getRelationshipDiffs().size());
    }

    @Test
    @DisplayName("NormalizedRel 메모리 효율성 검증 - 대량 관계 처리")
    void normalizedRelShouldHandleLargeNumberOfRelationships() {
        // oldEntity는 빈 관계, newEntity는 200개 복잡한 관계 (메모리 효율성 테스트)
        EntityModel oldEntity = EntityModel.builder()
                .entityName("OldEntity")
                .relationships(new HashMap<>())
                .build();
        EntityModel newEntity = createComplexEntity("NewEntity", 200);
        
        DiffResult.ModifiedEntity result = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        // OutOfMemoryError 없이 완료되어야 함
        assertDoesNotThrow(() -> {
            relationshipDiffer.diff(oldEntity, newEntity, result);
        });

        // 200개의 새로운 관계가 추가되어야 함
        assertEquals(200, result.getRelationshipDiffs().size());
        
        // 경고 메시지가 있어도 정상 동작해야 함
        assertNotNull(result.getWarnings());
    }

    private EntityModel createEntityWithManyRelationships(String entityName, int count) {
        Map<String, RelationshipModel> relationships = new HashMap<>();
        
        for (int i = 0; i < count; i++) {
            RelationshipModel rel = RelationshipModel.builder()
                    .type(RelationshipType.MANY_TO_ONE)
                    .tableName(entityName)
                    .columns(List.of("ref_id_" + i))
                    .referencedTable("RefTable_" + i)
                    .referencedColumns(List.of("id"))
                    .constraintName("fk_" + entityName.toLowerCase() + "_ref_" + i)
                    .fetchType(FetchType.LAZY)
                    .orphanRemoval(false)
                    .mapsId(false)
                    .cascadeTypes(Collections.emptyList())
                    .build();
            
            relationships.put("ref" + i, rel);
        }

        return EntityModel.builder()
                .entityName(entityName)
                .relationships(relationships)
                .build();
    }

    private EntityModel createComplexEntity(String entityName, int count) {
        Map<String, RelationshipModel> relationships = new HashMap<>();
        
        for (int i = 0; i < count; i++) {
            // 다양한 복잡성을 가진 관계들 생성
            RelationshipModel rel = RelationshipModel.builder()
                    .type(i % 2 == 0 ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_MANY)
                    .tableName(entityName + "_TABLE")
                    .columns(List.of("col1_" + i, "col2_" + i))  // 복합 컬럼
                    .referencedTable("REF_TABLE_" + (i % 10))  // 몇 개의 참조 테이블 재사용
                    .referencedColumns(List.of("ref_id", "ref_uuid"))  // 복합 참조 컬럼
                    .constraintName("FK_COMPLEX_" + i)
                    .fetchType(i % 3 == 0 ? FetchType.EAGER : FetchType.LAZY)
                    .orphanRemoval(i % 4 == 0)
                    .mapsId(i % 10 == 0)  // 가끔 mapsId 활성화
                    .cascadeTypes(i % 5 == 0 ? List.of(CascadeType.PERSIST, CascadeType.MERGE) : Collections.emptyList())
                    .build();
            
            relationships.put("complex_rel_" + i, rel);
        }

        return EntityModel.builder()
                .entityName(entityName)
                .relationships(relationships)
                .build();
    }
}