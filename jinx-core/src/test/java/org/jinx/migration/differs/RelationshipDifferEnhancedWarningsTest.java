package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.OnDeleteAction;
import org.jinx.model.OnUpdateAction;
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

class RelationshipDifferEnhancedWarningsTest {

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
    @DisplayName("onDelete 변경 시 구체적인 경고를 생성해야 함")
    void shouldGenerateSpecificWarningsForOnDeleteChanges() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .onDelete(OnDeleteAction.NO_ACTION)
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
                .onDelete(OnDeleteAction.CASCADE)  // 변경됨
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Foreign key ON DELETE action changed") && 
            w.contains("from NO_ACTION to CASCADE") &&
            w.contains("affects referential integrity behavior") &&
            w.contains("Review dependent data")
        ), "onDelete 변경에 대한 구체적인 경고가 있어야 함");
    }

    @Test
    @DisplayName("onUpdate 변경 시 구체적인 경고를 생성해야 함")
    void shouldGenerateSpecificWarningsForOnUpdateChanges() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .onUpdate(OnUpdateAction.NO_ACTION)
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
                .onUpdate(OnUpdateAction.SET_NULL)  // 변경됨
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Foreign key ON UPDATE action changed") && 
            w.contains("from NO_ACTION to SET_NULL") &&
            w.contains("affects referential integrity behavior")
        ), "onUpdate 변경에 대한 구체적인 경고가 있어야 함");
    }

    @Test
    @DisplayName("noConstraint 활성화 시 구체적인 경고를 생성해야 함")
    void shouldGenerateWarningsForNoConstraintEnabled() {
        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .noConstraint(false)  // 기존값
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
                .noConstraint(true)  // 활성화
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .mapsId(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Foreign key constraint disabled (NO_CONSTRAINT)") &&
            w.contains("referential integrity will no longer be enforced") &&
            w.contains("Ensure application-level validation")
        ), "noConstraint 활성화에 대한 구체적인 경고가 있어야 함");
    }

    @Test
    @DisplayName("mapsId 활성화 시 PK 승격 관련 경고를 생성해야 함")
    void shouldGenerateWarningsForMapsIdEnabled() {
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
                .mapsId(true)  // 활성화
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("@MapsId enabled") &&
            w.contains("foreign key columns will now be part of the primary key") &&
            w.contains("significant structural change") &&
            w.contains("affects entity identity")
        ), "mapsId 활성화에 대한 구체적인 PK 승격 경고가 있어야 함");
    }

    @Test
    @DisplayName("mapsIdBindings 변경 시 경고를 생성해야 함")
    void shouldGenerateWarningsForMapsIdBindingsChanges() {
        Map<String, String> oldBindings = Map.of("team_id", "id");
        Map<String, String> newBindings = Map.of("team_id", "uuid");

        RelationshipModel oldRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .mapsId(true)
                .mapsIdBindings(oldBindings)
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
                .mapsId(true)
                .mapsIdBindings(newBindings)  // 변경됨
                .fetchType(FetchType.LAZY)
                .orphanRemoval(false)
                .cascadeTypes(Collections.emptyList())
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("@MapsId column bindings changed") &&
            w.contains("affects primary key composition")
        ), "mapsIdBindings 변경에 대한 경고가 있어야 함");
    }

    @Test
    @DisplayName("cascadeTypes 순서 변경은 오탐으로 감지하지 않아야 함")
    void shouldNotTriggerFalsePositiveForCascadeTypeOrderChanges() {
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

        // cascadeTypes 순서만 다르므로 경고가 없어야 함
        List<String> warnings = modifiedEntityResult.getWarnings();
        assertFalse(warnings.stream().anyMatch(w -> w.contains("cascade options changed")),
                "cascadeTypes 순서 변경만으로는 경고가 발생하지 않아야 함");
    }

    @Test
    @DisplayName("cascadeTypes 실제 변경 시에는 EnumSet 기반으로 정확한 경고를 생성해야 함")
    void shouldGenerateAccurateWarningsForActualCascadeTypeChanges() {
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
                .cascadeTypes(List.of(CascadeType.PERSIST, CascadeType.REMOVE, CascadeType.MERGE))  // 추가됨
                .build();

        oldEntity.getRelationships().put("team", oldRel);
        newEntity.getRelationships().put("team", newRel);

        relationshipDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> 
            w.contains("Persistence cascade options changed") &&
            w.contains("added: [") &&
            (w.contains("REMOVE") && w.contains("MERGE")) &&  // 추가된 cascade types
            w.contains("may affect automatic persistence operations")
        ), "실제 cascadeTypes 변경에 대한 정확한 경고가 있어야 함");
    }
}