package org.jinx.migration.differs;

import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TableDiffer class.
 * Verifies that added, dropped, and renamed tables are correctly detected.
 */
class TableDifferTest {

    private TableDiffer tableDiffer;

    @BeforeEach
    void setUp() {
        tableDiffer = new TableDiffer();
    }

    @Test
    @DisplayName("테이블 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenSchemasAreIdentical() {
        EntityModel userEntity = createEntity("User", "users",
                createColumn("id", true, "BIGINT"),
                createColumn("username", false, "VARCHAR")
        );
        SchemaModel oldSchema = createSchema("v1", userEntity);
        SchemaModel newSchema = createSchema("v2", userEntity);
        DiffResult result = DiffResult.builder().build();

        tableDiffer.diff(oldSchema, newSchema, result);

        assertTrue(result.getAddedTables().isEmpty(), "추가된 테이블이 없어야 합니다.");
        assertTrue(result.getDroppedTables().isEmpty(), "삭제된 테이블이 없어야 합니다.");
        assertTrue(result.getRenamedTables().isEmpty(), "이름이 변경된 테이블이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 테이블이 추가되었을 때 정확히 감지해야 함")
    void shouldDetectAddedTable() {
        EntityModel userEntity = createEntity("User", "users", createColumn("id", true, "BIGINT"));
        EntityModel postEntity = createEntity("Post", "posts", createColumn("id", true, "BIGINT"));

        SchemaModel oldSchema = createSchema("v1", userEntity);
        SchemaModel newSchema = createSchema("v2", userEntity, postEntity);
        DiffResult result = DiffResult.builder().build();

        tableDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getAddedTables().size(), "추가된 테이블은 1개여야 합니다.");
        assertEquals("Post", result.getAddedTables().get(0).getEntityName(), "추가된 테이블의 이름은 'Post'여야 합니다.");
        assertTrue(result.getDroppedTables().isEmpty(), "삭제된 테이블이 없어야 합니다.");
        assertTrue(result.getRenamedTables().isEmpty(), "이름이 변경된 테이블이 없어야 합니다.");
    }

    @Test
    @DisplayName("기존 테이블이 삭제되었을 때 정확히 감지해야 함")
    void shouldDetectDroppedTable() {
        EntityModel userEntity = createEntity("User", "users", createColumn("id", true, "BIGINT"));
        EntityModel postEntity = createEntity("Post", "posts", createColumn("id", true, "BIGINT"));

        SchemaModel oldSchema = createSchema("v1", userEntity, postEntity);
        SchemaModel newSchema = createSchema("v2", userEntity);
        DiffResult result = DiffResult.builder().build();

        tableDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getDroppedTables().size(), "삭제된 테이블은 1개여야 합니다.");
        assertEquals("Post", result.getDroppedTables().get(0).getEntityName(), "삭제된 테이블의 이름은 'Post'여야 합니다.");
        assertTrue(result.getAddedTables().isEmpty(), "추가된 테이블이 없어야 합니다.");
        assertTrue(result.getRenamedTables().isEmpty(), "이름이 변경된 테이블이 없어야 합니다.");
    }

    @Test
    @DisplayName("테이블 이름만 변경되었을 때 'Renamed'로 정확히 감지해야 함")
    void shouldDetectRenamedTable_whenOnlyNameChanges() {
        // PK와 컬럼 속성은 동일하고, 엔티티/테이블 이름만 다름
        EntityModel oldEntity = createEntity("OldUser", "old_users",
                createColumn("id", true, "BIGINT"),
                createColumn("name", false, "VARCHAR")
        );
        EntityModel newEntity = createEntity("NewUser", "new_users",
                createColumn("id", true, "BIGINT"),
                createColumn("name", false, "VARCHAR")
        );

        SchemaModel oldSchema = createSchema("v1", oldEntity);
        SchemaModel newSchema = createSchema("v2", newEntity);
        DiffResult result = DiffResult.builder().build();

        tableDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getRenamedTables().size(), "이름이 변경된 테이블은 1개여야 합니다.");
        assertEquals("OldUser", result.getRenamedTables().get(0).getOldEntity().getEntityName());
        assertEquals("NewUser", result.getRenamedTables().get(0).getNewEntity().getEntityName());
        assertTrue(result.getAddedTables().isEmpty(), "이름 변경 시 추가된 테이블로 감지되면 안 됩니다.");
        assertTrue(result.getDroppedTables().isEmpty(), "이름 변경 시 삭제된 테이블로 감지되면 안 됩니다.");
    }

    @Test
    @DisplayName("테이블 이름과 컬럼 속성이 함께 변경되면 'Dropped'과 'Added'로 감지해야 함")
    void shouldDetectAsDropAndAdd_whenNameAndColumnAttributesChange() {
        // 이름은 다르지만, 컬럼 속성(javaType)도 달라져서 해시가 달라짐
        EntityModel oldEntity = createEntity("User", "users",
                createColumn("id", true, "BIGINT"),
                createColumn("name", false, "VARCHAR") // old type
        );
        EntityModel newEntity = createEntity("Customer", "customers",
                createColumn("id", true, "BIGINT"),
                createColumn("name", false, "TEXT") // new type, this will change the hash
        );

        SchemaModel oldSchema = createSchema("v1", oldEntity);
        SchemaModel newSchema = createSchema("v2", newEntity);
        DiffResult result = DiffResult.builder().build();

        tableDiffer.diff(oldSchema, newSchema, result);

        assertTrue(result.getRenamedTables().isEmpty(), "이름 변경으로 감지되면 안 됩니다.");
        assertEquals(1, result.getDroppedTables().size(), "삭제된 테이블은 1개여야 합니다.");
        assertEquals("User", result.getDroppedTables().get(0).getEntityName());
        assertEquals(1, result.getAddedTables().size(), "추가된 테이블은 1개여야 합니다.");
        assertEquals("Customer", result.getAddedTables().get(0).getEntityName());
    }

    @Test
    @DisplayName("추가, 삭제, 이름 변경이 섞여 있을 때 모두 정확히 감지해야 함")
    void shouldDetectAllChangesInMixedScenario() {
        // Unchanged
        EntityModel productEntity = createEntity("Product", "products", createColumn("pid", true, "BIGINT"));

        // Dropped
        EntityModel tagEntity = createEntity("Tag", "tags", createColumn("tid", true, "BIGINT"));

        // Renamed
        EntityModel oldOrderEntity = createEntity("Order", "orders", createColumn("oid", true, "BIGINT"));
        EntityModel newPurchaseEntity = createEntity("Purchase", "purchases", createColumn("oid", true, "BIGINT"));

        // Added
        EntityModel reviewEntity = createEntity("Review", "reviews", createColumn("rid", true, "BIGINT"));

        SchemaModel oldSchema = createSchema("v1", productEntity, tagEntity, oldOrderEntity);
        SchemaModel newSchema = createSchema("v2", productEntity, newPurchaseEntity, reviewEntity);
        DiffResult result = DiffResult.builder().build();

        tableDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getAddedTables().size(), "추가된 테이블은 1개여야 합니다.");
        assertEquals("Review", result.getAddedTables().get(0).getEntityName());

        assertEquals(1, result.getDroppedTables().size(), "삭제된 테이블은 1개여야 합니다.");
        assertEquals("Tag", result.getDroppedTables().get(0).getEntityName());

        assertEquals(1, result.getRenamedTables().size(), "이름이 변경된 테이블은 1개여야 합니다.");
        assertEquals("Order", result.getRenamedTables().get(0).getOldEntity().getEntityName());
        assertEquals("Purchase", result.getRenamedTables().get(0).getNewEntity().getEntityName());
    }

    private SchemaModel createSchema(String version, EntityModel... entities) {
        SchemaModel schema = new SchemaModel();
        schema.setVersion(version);
        if (entities != null) {
            schema.setEntities(Arrays.stream(entities)
                    .collect(Collectors.toMap(EntityModel::getEntityName, e -> e)));
        } else {
            schema.setEntities(Collections.emptyMap());
        }
        return schema;
    }

    private EntityModel createEntity(String entityName, String tableName, ColumnModel... columns) {
        EntityModel entity = new EntityModel();
        entity.setEntityName(entityName);
        entity.setTableName(tableName);
        if (columns != null) {
            entity.setColumns(Arrays.stream(columns)
                    .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c)));
        } else {
            entity.setColumns(Collections.emptyMap());
        }
        return entity;
    }

    private ColumnModel createColumn(String name, boolean isPk, String javaType) {
        return ColumnModel.builder()
                .columnName(name)
                .isPrimaryKey(isPk)
                .javaType(javaType)
                .build();
    }
}
