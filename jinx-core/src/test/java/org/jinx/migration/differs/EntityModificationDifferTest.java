package org.jinx.migration.differs;

import jakarta.persistence.InheritanceType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityModificationDifferTest {

    private SchemaModel mockSchemaWithEntities(Map<String, EntityModel> entities) {
        SchemaModel schema = mock(SchemaModel.class);
        when(schema.getEntities()).thenReturn(entities);
        return schema;
    }

    private EntityModel entity(String name, String table, String schema, String catalog, InheritanceType inh) {
        return EntityModel.builder()
                .entityName(name)
                .tableName(table)
                .schema(schema)
                .catalog(catalog)
                .inheritance(inh)
                .build();
    }

    @Test
    void diff_collectsWarningsAndModified_whenMatchingEntityHasEntityLevelChanges() {
        // given
        var oldEntities = new LinkedHashMap<String, EntityModel>();
        var newEntities = new LinkedHashMap<String, EntityModel>();

        oldEntities.put("User", entity("User", "t_user", "old_s", "old_c", InheritanceType.SINGLE_TABLE));
        newEntities.put("User", entity("User", "t_user", "new_s", "new_c", InheritanceType.JOINED));

        SchemaModel oldSchema = mockSchemaWithEntities(oldEntities);
        SchemaModel newSchema = mockSchemaWithEntities(newEntities);

        EntityModificationDiffer differ = new EntityModificationDiffer(CaseNormalizer.lower());

        // when
        DiffResult out = DiffResult.builder().build();
        differ.diff(oldSchema, newSchema, out);

        // then
        assertEquals(1, out.getModifiedTables().size(), "Should have one modified entity");
        // Entity-level warnings are bubbled up to root warnings as well
        assertTrue(out.getWarnings().stream().anyMatch(s -> s.contains("Schema changed: old_s → new_s")),
                "Schema change warning should be present");
        assertTrue(out.getWarnings().stream().anyMatch(s -> s.contains("Catalog changed: old_c → new_c")),
                "Catalog change warning should be present");
        assertTrue(out.getWarnings().stream().anyMatch(s -> s.contains("Inheritance strategy changed: SINGLE_TABLE → JOINED")),
                "Inheritance change warning should be present");
    }

    @Test
    void diff_ignoresEntitiesOnlyInNewSchema() {
        // given
        SchemaModel oldSchema = mockSchemaWithEntities(Map.of()); // old has none
        var newEntities = new LinkedHashMap<String, EntityModel>();
        newEntities.put("OnlyNew", entity("OnlyNew", "t_new", null, null, null));
        SchemaModel newSchema = mockSchemaWithEntities(newEntities);

        EntityModificationDiffer differ = new EntityModificationDiffer();

        // when
        DiffResult out = DiffResult.builder().build();
        differ.diff(oldSchema, newSchema, out);

        // then
        assertTrue(out.getModifiedTables().isEmpty(), "No modified tables expected when there is no name match");
        assertTrue(out.getWarnings().isEmpty(), "No warnings expected");
    }

    @Test
    void diff_doesNothing_whenEntitiesMapsAreNull() {
        // given
        SchemaModel oldSchema = mock(SchemaModel.class);
        when(oldSchema.getEntities()).thenReturn(null);
        SchemaModel newSchema = mock(SchemaModel.class);
        when(newSchema.getEntities()).thenReturn(null);

        EntityModificationDiffer differ = new EntityModificationDiffer();

        // when
        DiffResult out = DiffResult.builder().build();
        assertDoesNotThrow(() -> differ.diff(oldSchema, newSchema, out));

        // then
        assertTrue(out.getModifiedTables().isEmpty());
        assertTrue(out.getWarnings().isEmpty());
    }

    @Test
    void diff_doesNotReport_whenNoEntityLevelChanges_andComponentsProduceNoDiffs() {
        // given
        var oldEntities = new LinkedHashMap<String, EntityModel>();
        var newEntities = new LinkedHashMap<String, EntityModel>();

        // same values
        oldEntities.put("User", entity("User", "t_user", "s", "c", InheritanceType.SINGLE_TABLE));
        newEntities.put("User", entity("User", "t_user", "s", "c", InheritanceType.SINGLE_TABLE));

        SchemaModel oldSchema = mockSchemaWithEntities(oldEntities);
        SchemaModel newSchema = mockSchemaWithEntities(newEntities);

        EntityModificationDiffer differ = new EntityModificationDiffer(CaseNormalizer.lower());

        // when
        DiffResult out = DiffResult.builder().build();
        differ.diff(oldSchema, newSchema, out);

        // then
        // 주의: Column/Index/Constraint/Relationship differ들이 실제 변경을 낸다면 이 테스트는 달라질 수 있습니다.
        // 현재 전제는 "컴포넌트 디퍼가 빈 엔티티에서 변경을 만들지 않는다"입니다.
        assertTrue(out.getModifiedTables().isEmpty(), "No modified tables expected when nothing changed");
        assertTrue(out.getWarnings().isEmpty(), "No warnings expected");
    }

    @Test
    void diffPair_collectsIntoResult_like_singleEntityPath() {
        // given
        EntityModel oldE = entity("Old", "t", "s1", "c1", InheritanceType.SINGLE_TABLE);
        EntityModel newE = entity("New", "t", "s2", "c2", InheritanceType.JOINED);

        EntityModificationDiffer differ = new EntityModificationDiffer();

        // when
        DiffResult out = DiffResult.builder().build();
        differ.diffPair(oldE, newE, out);

        // then
        assertEquals(1, out.getModifiedTables().size(), "modifiedTables should include this pair");
        assertTrue(out.getWarnings().stream().anyMatch(s -> s.contains("Schema changed: s1 → s2")));
        assertTrue(out.getWarnings().stream().anyMatch(s -> s.contains("Catalog changed: c1 → c2")));
        assertTrue(out.getWarnings().stream().anyMatch(s -> s.contains("Inheritance strategy changed: SINGLE_TABLE → JOINED")));
    }
}
