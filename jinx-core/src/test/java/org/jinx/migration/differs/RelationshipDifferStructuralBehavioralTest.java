package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.OnDeleteAction;
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

class RelationshipDifferStructuralBehavioralTest {

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
    @DisplayName("구조적 변경은 requiresDropAdd=true이고 [STRUCTURAL] 태그가 있어야 함")
    void shouldMarkStructuralChangesAsRequiringDropAdd() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .onDelete(OnDeleteAction.NO_ACTION)  // 기존값
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
                .onDelete(OnDeleteAction.CASCADE)  // 구조적 변경
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
        assertTrue(diff.getRequiresDropAdd(), "구조적 변경은 requiresDropAdd=true여야 함");
        assertTrue(diff.getChangeDetail().contains("[STRUCTURAL]"), "구조적 변경은 [STRUCTURAL] 태그가 있어야 함");
        assertTrue(diff.getChangeDetail().contains("onDelete changed"));
        assertFalse(diff.getChangeDetail().contains("[BEHAVIORAL]"), "구조적 변경만 있으므로 [BEHAVIORAL] 태그가 없어야 함");
    }

    @Test
    @DisplayName("비구조적 변경은 requiresDropAdd=false이고 [BEHAVIORAL] 태그가 있어야 함")
    void shouldMarkBehavioralChangesAsNotRequiringDropAdd() {
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
                .fetchType(FetchType.EAGER)  // 비구조적 변경
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
        assertFalse(diff.getRequiresDropAdd(), "비구조적 변경은 requiresDropAdd=false여야 함");
        assertTrue(diff.getChangeDetail().contains("[BEHAVIORAL]"), "비구조적 변경은 [BEHAVIORAL] 태그가 있어야 함");
        assertTrue(diff.getChangeDetail().contains("fetchType changed"));
        assertFalse(diff.getChangeDetail().contains("[STRUCTURAL]"), "비구조적 변경만 있으므로 [STRUCTURAL] 태그가 없어야 함");
    }

    @Test
    @DisplayName("구조적+비구조적 혼합 변경은 requiresDropAdd=true이고 둘 다 태그가 있어야 함")
    void shouldMarkMixedChangesCorrectly() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .constraintName("old_fk")  // 구조적 변경 대상
                .fetchType(FetchType.LAZY)  // 비구조적 변경 대상
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
                .constraintName("new_fk")  // 구조적 변경
                .fetchType(FetchType.EAGER)  // 비구조적 변경
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
        assertTrue(diff.getRequiresDropAdd(), "구조적 변경이 포함되면 requiresDropAdd=true여야 함");
        assertTrue(diff.getChangeDetail().contains("[STRUCTURAL]"), "구조적 변경 태그가 있어야 함");
        assertTrue(diff.getChangeDetail().contains("[BEHAVIORAL]"), "비구조적 변경 태그가 있어야 함");
        assertTrue(diff.getChangeDetail().contains("constraintName changed"));
        assertTrue(diff.getChangeDetail().contains("fetchType changed"));
        assertTrue(diff.getChangeDetail().contains(" | "), "두 태그 사이에 구분자가 있어야 함");
    }

    @Test
    @DisplayName("mapsId 변경은 PK 승격에 영향을 주므로 구조적 변경으로 분류해야 함")
    void shouldTreatMapsIdChangeAsStructural() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .mapsId(false)  // 기존값
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        RelationshipModel newRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .mapsId(true)  // PK 승격 영향
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getRequiresDropAdd(), "mapsId 변경은 구조적 변경이므로 requiresDropAdd=true여야 함");
        assertTrue(diff.getChangeDetail().contains("[STRUCTURAL]"), "mapsId 변경은 [STRUCTURAL] 태그가 있어야 함");
        assertTrue(diff.getChangeDetail().contains("mapsId changed"));
    }

    @Test
    @DisplayName("cascadeTypes 변경은 비구조적이므로 requiresDropAdd=false여야 함")
    void shouldTreatCascadeTypesChangeAsBehavioral() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(List.of(CascadeType.PERSIST))  // 기존값
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
                .cascadeTypes(List.of(CascadeType.PERSIST, CascadeType.REMOVE))  // 런타임 의미 변경
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertFalse(diff.getRequiresDropAdd(), "cascadeTypes 변경은 비구조적이므로 requiresDropAdd=false여야 함");
        assertTrue(diff.getChangeDetail().contains("[BEHAVIORAL]"), "cascadeTypes 변경은 [BEHAVIORAL] 태그가 있어야 함");
        assertTrue(diff.getChangeDetail().contains("cascadeTypes changed"));
        assertFalse(diff.getChangeDetail().contains("[STRUCTURAL]"), "비구조적 변경만 있으므로 [STRUCTURAL] 태그가 없어야 함");
    }
}