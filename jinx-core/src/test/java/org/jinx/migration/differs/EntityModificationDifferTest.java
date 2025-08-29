package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;


class EntityModificationDifferTest {
    @Mock
    private ColumnDiffer columnDiffer;
    @Mock
    private IndexDiffer indexDiffer;
    @Mock
    private ConstraintDiffer constraintDiffer;
    @Mock
    private RelationshipDiffer relationshipDiffer;

    @InjectMocks
    private EntityModificationDiffer entityModificationDiffer;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Manually inject the list of mocked differs into the private final field.
        Field differsField = EntityModificationDiffer.class.getDeclaredField("componentDiffers");
        differsField.setAccessible(true);
        List<EntityComponentDiffer> mockDiffers = Arrays.asList(columnDiffer, indexDiffer, constraintDiffer, relationshipDiffer);
        differsField.set(entityModificationDiffer, mockDiffers);
    }

    @Test
    @DisplayName("공통된 엔티티가 없을 때 아무런 변경도 감지하지 않아야 함")
    void shouldDetectNoModifications_whenNoCommonEntities() {
        SchemaModel oldSchema = createSchemaWithEntities(createEntity("User"));
        SchemaModel newSchema = createSchemaWithEntities(createEntity("Customer"));
        DiffResult result = DiffResult.builder().build();

        entityModificationDiffer.diff(oldSchema, newSchema, result);

        assertTrue(result.getModifiedTables().isEmpty(), "수정된 테이블이 없어야 합니다.");
    }

    @Test
    @DisplayName("엔티티가 존재할 때 모든 하위 Differ에게 위임해야 함")
    void shouldDelegateToAllComponentDiffers_whenEntityIsCommon() {
        EntityModel oldEntity = createEntity("User");
        EntityModel newEntity = createEntity("User");
        SchemaModel oldSchema = createSchemaWithEntities(oldEntity);
        SchemaModel newSchema = createSchemaWithEntities(newEntity);
        DiffResult result = DiffResult.builder().build();

        entityModificationDiffer.diff(oldSchema, newSchema, result);

        verify(columnDiffer).diff(any(EntityModel.class), any(EntityModel.class), any(DiffResult.ModifiedEntity.class));
        verify(indexDiffer).diff(any(EntityModel.class), any(EntityModel.class), any(DiffResult.ModifiedEntity.class));
        verify(constraintDiffer).diff(any(EntityModel.class), any(EntityModel.class), any(DiffResult.ModifiedEntity.class));
        verify(relationshipDiffer).diff(any(EntityModel.class), any(EntityModel.class), any(DiffResult.ModifiedEntity.class));
    }

    @Test
    @DisplayName("하위 Differ가 변경을 감지하면 'ModifiedEntity'를 결과에 추가해야 함")
    void shouldAddModifiedEntity_whenComponentDifferFindsChange() {
        EntityModel oldEntity = createEntity("User");
        EntityModel newEntity = createEntity("User");
        SchemaModel oldSchema = createSchemaWithEntities(oldEntity);
        SchemaModel newSchema = createSchemaWithEntities(newEntity);
        DiffResult result = DiffResult.builder().build();

        doAnswer(invocation -> {
            DiffResult.ModifiedEntity modified = invocation.getArgument(2);
            modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder().build());
            return null;
        }).when(columnDiffer).diff(any(), any(), any());

        entityModificationDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getModifiedTables().size(), "수정된 테이블은 1개여야 합니다.");
        assertFalse(result.getModifiedTables().get(0).getColumnDiffs().isEmpty(), "컬럼 변경 내역이 포함되어야 합니다.");
    }

    @Test
    @DisplayName("엔티티의 스키마나 카탈로그가 변경되면 경고를 추가해야 함")
    void shouldAddWarning_whenSchemaOrCatalogChanges() {
        EntityModel oldEntity = createEntity("User");
        oldEntity.setSchema("dbo");
        oldEntity.setCatalog("main_db");

        EntityModel newEntity = createEntity("User");
        newEntity.setSchema("public");
        newEntity.setCatalog("new_db");

        SchemaModel oldSchema = createSchemaWithEntities(oldEntity);
        SchemaModel newSchema = createSchemaWithEntities(newEntity);
        DiffResult result = DiffResult.builder().build();

        entityModificationDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getModifiedTables().size(), "수정된 테이블은 1개여야 합니다.");
        List<String> warnings = result.getModifiedTables().get(0).getWarnings();
        assertEquals(2, warnings.size(), "경고는 2개여야 합니다.");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Schema changed from dbo to public")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Catalog changed from main_db to new_db")));
    }

    private SchemaModel createSchemaWithEntities(EntityModel... entities) {
        SchemaModel schema = new SchemaModel();
        if (entities != null) {
            schema.setEntities(Arrays.stream(entities)
                    .collect(java.util.stream.Collectors.toMap(EntityModel::getEntityName, e -> e)));
        } else {
            schema.setEntities(Collections.emptyMap());
        }
        return schema;
    }

    private EntityModel createEntity(String name) {
        EntityModel entity = new EntityModel();
        entity.setEntityName(name);
        entity.setTableName(name.toLowerCase() + "s");
        return entity;
    }
}
