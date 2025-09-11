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

import static org.junit.jupiter.api.Assertions.*;

class RelationshipDifferNullSafetyTest {

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
    @DisplayName("constraintName이 null인 관계는 NPE 없이 비교되어야 함")
    void shouldHandleNullConstraintNamesWithoutNPE() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName(null)  // null 값
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
                .constraintName(null)  // null 값 (동일)
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        // NPE 없이 정상적으로 실행되어야 함
        assertDoesNotThrow(() -> {
            relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
        });

        // null 값이 동일하므로 변경사항이 없어야 함
        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), 
            "null constraintName이 동일하므로 변경사항이 없어야 함");
    }

    @Test
    @DisplayName("constraintName이 null에서 non-null로 변경되어도 NPE 없이 감지되어야 함")
    void shouldDetectConstraintNameChangeFromNullToNonNullWithoutNPE() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName(null)  // null 값
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
                .constraintName("fk_user_team")  // non-null 값으로 변경
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        // NPE 없이 정상적으로 실행되어야 함
        assertDoesNotThrow(() -> {
            relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
        });

        // null에서 non-null로의 변경이 감지되어야 함
        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("constraintName changed from null to fk_user_team"));
    }

    @Test
    @DisplayName("constraintName이 non-null에서 null로 변경되어도 NPE 없이 감지되어야 함")
    void shouldDetectConstraintNameChangeFromNonNullToNullWithoutNPE() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName("fk_user_team")  // non-null 값
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
                .constraintName(null)  // null 값으로 변경
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        // NPE 없이 정상적으로 실행되어야 함
        assertDoesNotThrow(() -> {
            relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
        });

        // non-null에서 null로의 변경이 감지되어야 함
        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("constraintName changed from fk_user_team to null"));
    }

    @Test
    @DisplayName("대소문자만 다른 constraintName은 동일하다고 판단해야 함 (null-safe 정규화)")
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

        // 대소문자만 다른 constraintName은 동일하다고 판단되어야 함
        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), 
            "대소문자만 다른 constraintName은 동일하다고 판단되어야 함");
    }

    @Test
    @DisplayName("requiresDropAdd 로직도 null constraintName을 안전하게 처리해야 함")
    void shouldHandleNullConstraintNameInRequiresDropAddLogic() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName(null)  // null 값
                .fetchType(FetchType.LAZY)  // 비구조적 변경
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
                .constraintName(null)  // null 값 (동일)
                .fetchType(FetchType.EAGER)  // 비구조적 변경
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        assertDoesNotThrow(() -> {
            relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
        });

        // constraintName이 동일(null)하고 fetchType만 변경되었으므로 비구조적 변경
        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertFalse(diff.getRequiresDropAdd(), 
            "constraintName이 동일하고 비구조적 변경만 있으므로 requiresDropAdd=false여야 함");
    }
}