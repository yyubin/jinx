package org.jinx.migration.differs;

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

class RelationshipDifferDuplicateKeyDetectionTest {

    private RelationshipDiffer relationshipDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        relationshipDiffer = new RelationshipDiffer(CaseNormalizer.lower());
        oldEntity = EntityModel.builder()
                .entityName("User")
                .relationships(new HashMap<>())
                .build();
        newEntity = EntityModel.builder()
                .entityName("User")
                .relationships(new HashMap<>())
                .build();
        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();
    }

    @Test
    @DisplayName("동일한 RelationshipKey를 가진 중복 관계가 있을 때 경고를 생성해야 함")
    void shouldWarnnForDuplicateRelationshipKeys() {
        // 동일한 키를 가지는 두 관계 생성 (서로 다른 속성명으로 구분)
        RelationshipModel rel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("team")  // 첫 번째 관계
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)  // 동일한 type
                .tableName("User")  // 동일한 tableName
                .columns(List.of("team_id"))  // 동일한 columns
                .referencedTable("Team")  // 동일한 referencedTable
                .referencedColumns(List.of("id"))  // 동일한 referencedColumns
                .sourceAttributeName("teamReference")  // 두 번째 관계 (다른 속성명)
                .fetchType(FetchType.EAGER)  // 다른 fetch type (키에 영향 없음)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        // 두 관계 모두 새 엔티티에 추가 (동일한 키를 가짐)
        newEntity.getRelationships().put("team", rel1);
        newEntity.getRelationships().put("teamReference", rel2);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // 중복 키 감지 경고가 있어야 함
        List<String> warnings = modifiedEntityResult.getWarnings();
        
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Duplicate relationships collapsed by key") &&
            w.contains("new entity 'User'") &&
            w.contains("team_id") &&
            w.contains("Second relationship will overwrite the first")
        ), "중복 관계 키에 대한 경고가 있어야 함");
    }

    @Test
    @DisplayName("old 엔티티에서도 중복 관계 키를 감지해야 함")
    void shouldDetectDuplicateKeysInOldEntity() {
        RelationshipModel oldRel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("dept_id"))
                .referencedTable("Department")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("department")
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel oldRel2 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("dept_id"))
                .referencedTable("Department")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("dept")  // 다른 속성명이지만 동일한 키
                .fetchType(FetchType.EAGER)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("department", oldRel1);
        oldEntity.getRelationships().put("dept", oldRel2);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // old 엔티티의 중복 키 감지 경고가 있어야 함
        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Duplicate relationships collapsed by key") &&
            w.contains("old entity 'User'") &&
            w.contains("dept_id") &&
            w.contains("Second relationship will overwrite the first")
        ), "old 엔티티의 중복 관계 키에 대한 경고가 있어야 함");
    }

    @Test
    @DisplayName("대소문자만 다른 관계들도 중복으로 감지해야 함 (정규화된 키 기반)")
    void shouldDetectCaseInsensitiveDuplicates() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("USER")  // 대문자
                .columns(List.of("TEAM_ID"))  // 대문자
                .referencedTable("TEAM")  // 대문자
                .referencedColumns(List.of("ID"))  // 대문자
                .sourceAttributeName("team1")
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
                .sourceAttributeName("team2")
                .fetchType(FetchType.EAGER)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        newEntity.getRelationships().put("team1", rel1);
        newEntity.getRelationships().put("team2", rel2);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // 정규화된 키가 동일하므로 중복으로 감지되어야 함
        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Duplicate relationships collapsed by key") &&
            w.contains("team_id") &&
            w.contains("Second relationship will overwrite the first")
        ), "대소문자만 다른 관계들도 중복으로 감지되어야 함");
    }

    @Test
    @DisplayName("중복이 없는 정상적인 관계들은 경고를 생성하지 않아야 함")
    void shouldNotWarnForNormalNonDuplicateRelationships() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("team")
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("dept_id"))  // 다른 컬럼
                .referencedTable("Department")  // 다른 테이블
                .referencedColumns(List.of("id"))
                .sourceAttributeName("department")
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        newEntity.getRelationships().put("team", rel1);
        newEntity.getRelationships().put("department", rel2);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // 중복이 없으므로 중복 관련 경고가 없어야 함
        List<String> warnings = modifiedEntityResult.getWarnings();
        assertFalse(warnings.stream().anyMatch(w -> 
            w.contains("Duplicate relationships collapsed by key")
        ), "중복이 없는 정상적인 관계들은 중복 경고를 생성하지 않아야 함");
    }

    @Test
    @DisplayName("3개 이상의 중복 관계도 모두 감지해야 함")
    void shouldDetectMultipleDuplicates() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("team1")
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("team2")
                .fetchType(FetchType.EAGER)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel rel3 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("team3")
                .fetchType(FetchType.LAZY)
                .orphanRemoval(true)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        newEntity.getRelationships().put("team1", rel1);
        newEntity.getRelationships().put("team2", rel2);
        newEntity.getRelationships().put("team3", rel3);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // 3개 관계가 모두 동일한 키를 가지므로 2번의 중복 경고가 있어야 함
        // (team1 -> team2, team2 -> team3)
        List<String> warnings = modifiedEntityResult.getWarnings();
        long duplicateWarningCount = warnings.stream()
                .filter(w -> w.contains("Duplicate relationships collapsed by key"))
                .count();
        assertEquals(2, duplicateWarningCount, "3개 중복 관계에 대해 2번의 중복 경고가 있어야 함");
    }
}