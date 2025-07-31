package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class RelationshipDifferTest {

    private RelationshipDiffer relationshipDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        relationshipDiffer = new RelationshipDiffer();
        oldEntity = new EntityModel();
        oldEntity.setEntityName("User");
        newEntity = new EntityModel();
        newEntity.setEntityName("User");
        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();
    }

    @Test
    @DisplayName("관계 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenRelationshipsAreIdentical() {
        RelationshipModel rel = createRelationship("team_id", "Team", FetchType.EAGER, false);
        oldEntity.setRelationships(Collections.singletonList(rel));
        newEntity.setRelationships(Collections.singletonList(rel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 관계가 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedRelationship() {
        RelationshipModel newRel = createRelationship("team_id", "Team", FetchType.EAGER, false);
        oldEntity.setRelationships(Collections.emptyList());
        newEntity.setRelationships(Collections.singletonList(newRel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.ADDED, diff.getType());
        assertEquals("team_id", diff.getRelationship().getColumn());
    }

    @Test
    @DisplayName("기존 관계가 삭제되었을 때 'DROPPED'로 감지해야 함")
    void shouldDetectDroppedRelationship() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.EAGER, false);
        oldEntity.setRelationships(Collections.singletonList(oldRel));
        newEntity.setRelationships(Collections.emptyList());

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.DROPPED, diff.getType());
        assertEquals("team_id", diff.getRelationship().getColumn());
    }

    @Test
    @DisplayName("관계 속성이 변경되었을 때 'MODIFIED'로 감지하고 상세 내역과 경고를 생성해야 함")
    void shouldDetectModifiedRelationship_withDetailAndWarning() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.EAGER, false);
        oldRel.setCascadeTypes(Collections.singletonList(CascadeType.PERSIST));

        RelationshipModel newRel = createRelationship("team_id", "Team", FetchType.LAZY, true); // fetchType과 orphanRemoval 변경
        newRel.setCascadeTypes(List.of(CascadeType.PERSIST, CascadeType.REMOVE)); // cascadeType 변경

        oldEntity.setRelationships(Collections.singletonList(oldRel));
        newEntity.setRelationships(Collections.singletonList(newRel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // 1. MODIFIED 감지 확인
        assertEquals(1, modifiedEntityResult.getRelationshipDiffs().size());
        DiffResult.RelationshipDiff diff = modifiedEntityResult.getRelationshipDiffs().get(0);
        assertEquals(DiffResult.RelationshipDiff.Type.MODIFIED, diff.getType());
        assertEquals(oldRel, diff.getOldRelationship());
        assertEquals(newRel, diff.getRelationship());

        // 2. changeDetail 생성 확인
        assertNotNull(diff.getChangeDetail());
        assertTrue(diff.getChangeDetail().contains("cascadeTypes changed"));
        assertTrue(diff.getChangeDetail().contains("orphanRemoval changed from false to true"));
        assertTrue(diff.getChangeDetail().contains("fetchType changed from EAGER to LAZY"));

        // 3. warnings 생성 확인
        assertEquals(3, modifiedEntityResult.getWarnings().size());
        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Persistence cascade options changed") && w.contains("added: [REMOVE]")));
        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Orphan removal enabled")));
        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Fetch strategy changed")));
    }

    @Test
    @DisplayName("type, column, referencedTable, referencedColumn, mapsId 변경 시 감지되어야 함")
    void shouldDetectAllBasicPropertyChanges() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.LAZY, false);
        RelationshipModel newRel = createRelationship("team_id", "Group", FetchType.LAZY, false); // column 동일하게

        oldRel.setType(RelationshipType.MANY_TO_ONE);
        oldRel.setReferencedColumn("id");
        oldRel.setMapsId(false);

        newRel.setType(RelationshipType.ONE_TO_ONE);
        newRel.setReferencedColumn("uuid");
        newRel.setMapsId(true);

        oldEntity.setRelationships(List.of(oldRel));
        newEntity.setRelationships(List.of(newRel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        String detail = modifiedEntityResult.getRelationshipDiffs().get(0).getChangeDetail();

        assertAll(
                () -> assertTrue(detail.contains("type changed from MANY_TO_ONE to ONE_TO_ONE")),
                () -> assertTrue(detail.contains("referencedTable changed from Team to Group")),
                () -> assertTrue(detail.contains("referencedColumn changed from id to uuid")),
                () -> assertTrue(detail.contains("mapsId changed from false to true"))
        );
    }

    @Test
    @DisplayName("orphanRemoval 비활성화 시 경고 생성되어야 함")
    void shouldWarnWhenOrphanRemovalDisabled() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.LAZY, true); // originally true
        RelationshipModel newRel = createRelationship("team_id", "Team", FetchType.LAZY, false); // now false

        oldEntity.setRelationships(List.of(oldRel));
        newEntity.setRelationships(List.of(newRel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Orphan removal disabled")));
    }

    @Test
    @DisplayName("CascadeType 제거 시 'removed' 포함한 경고 발생해야 함")
    void shouldWarnWhenCascadeTypeRemoved() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.LAZY, false);
        oldRel.setCascadeTypes(List.of(CascadeType.PERSIST, CascadeType.REMOVE));

        RelationshipModel newRel = createRelationship("team_id", "Team", FetchType.LAZY, false);
        newRel.setCascadeTypes(List.of(CascadeType.PERSIST)); // REMOVE 제거됨

        oldEntity.setRelationships(List.of(oldRel));
        newEntity.setRelationships(List.of(newRel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("removed: [REMOVE]")));
    }

    @Test
    @DisplayName("changeDetail 마지막 세미콜론은 제거되어야 함")
    void shouldTrimTrailingSemicolonInChangeDetail() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.LAZY, false);
        RelationshipModel newRel = createRelationship("team_id", "Team", FetchType.EAGER, false);

        oldEntity.setRelationships(List.of(oldRel));
        newEntity.setRelationships(List.of(newRel));

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        String detail = modifiedEntityResult.getRelationshipDiffs().get(0).getChangeDetail();
        assertFalse(detail.endsWith(";"));
        assertFalse(detail.endsWith("; "));
    }

    @Test
    @DisplayName("type이 null인 관계는 DROP 대상에서 제외되어야 함")
    void shouldIgnoreDropWhenOldTypeIsNull() {
        RelationshipModel oldRel = createRelationship("team_id", "Team", FetchType.LAZY, false);
        oldRel.setType(null); // 중요한 조건

        oldEntity.setRelationships(List.of(oldRel));
        newEntity.setRelationships(Collections.emptyList());

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getRelationshipDiffs().isEmpty(), "DROP으로 감지되면 안 됩니다.");
    }

    private RelationshipModel createRelationship(String column, String referencedTable, FetchType fetchType, boolean orphanRemoval) {
        RelationshipModel rel = RelationshipModel.builder().build();
        rel.setType(RelationshipType.MANY_TO_ONE);
        rel.setColumn(column);
        rel.setReferencedTable(referencedTable);
        rel.setReferencedColumn("id");
        rel.setFetchType(fetchType);
        rel.setOrphanRemoval(orphanRemoval);
        rel.setMapsId(false);
        rel.setCascadeTypes(Collections.emptyList());
        return rel;
    }
}
