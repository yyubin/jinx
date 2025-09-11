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

import static org.junit.jupiter.api.Assertions.*;

class RelationshipDifferNormalizedComparisonTest {

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
    @DisplayName("대소문자가 다른 관계는 동일하다고 판단해야 함")
    void shouldTreatCaseInsensitiveRelationshipsAsEqual() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("USER")  // 대문자
                .columns(List.of("TEAM_ID"))  // 대문자
                .referencedTable("TEAM")  // 대문자
                .referencedColumns(List.of("ID"))  // 대문자
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel newRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("user")  // 소문자
                .columns(List.of("team_id"))  // 소문자
                .referencedTable("team")  // 소문자
                .referencedColumns(List.of("id"))  // 소문자
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), 
            "대소문자만 다른 관계는 동일하다고 판단해야 함");
    }

    @Test
    @DisplayName("constraintName 대소문자 차이는 동일하다고 판단해야 함")
    void shouldTreatCaseInsensitiveConstraintNamesAsEqual() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName("FK_USER_TEAM")  // 대문자
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel newRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName("fk_user_team")  // 소문자
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), 
            "constraintName의 대소문자 차이는 동일하다고 판단해야 함");
    }

    @Test
    @DisplayName("cascadeTypes 순서가 다른 경우 동일하다고 판단해야 함")
    void shouldTreatDifferentOrderCascadeTypesAsEqual() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(List.of(CascadeType.PERSIST, CascadeType.REMOVE))  // 순서 1
                .build();

        RelationshipModel newRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(List.of(CascadeType.REMOVE, CascadeType.PERSIST))  // 순서 2 (다름)
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), 
            "cascadeTypes의 순서 차이는 동일하다고 판단해야 함");
    }

    @Test
    @DisplayName("실제 관계 변경은 제대로 감지해야 함")
    void shouldDetectActualRelationshipChanges() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .fetchType(FetchType.LAZY)  // 기존값
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel newRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .fetchType(FetchType.EAGER)  // 변경됨
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("fetchType changed from LAZY to EAGER"));
    }

    @Test
    @DisplayName("누락된 필드들의 변경도 제대로 감지해야 함")
    void shouldDetectMissingFieldChanges() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName("old_constraint")
                .noConstraint(false)
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel newRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName("new_constraint")  // 변경됨
                .noConstraint(true)  // 변경됨
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("constraintName changed"));
        assertTrue(diff.getChangeDetail().contains("noConstraint changed"));
    }
}