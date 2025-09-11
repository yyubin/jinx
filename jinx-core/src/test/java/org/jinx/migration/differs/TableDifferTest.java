package org.jinx.migration.differs;

import org.jinx.migration.differs.model.TableKey;
import org.jinx.model.ColumnModel;
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

class TableDifferTest {

    private SchemaModel mockSchemaWithEntities(Map<String, EntityModel> entities) {
        SchemaModel schema = mock(SchemaModel.class);
        when(schema.getEntities()).thenReturn(entities);
        return schema;
    }

    private ColumnModel col(String table, String name, boolean pk, long hash) {
        ColumnModel c = mock(ColumnModel.class);
        when(c.getTableName()).thenReturn(table);
        when(c.getColumnName()).thenReturn(name);
        when(c.isPrimaryKey()).thenReturn(pk);
        when(c.getAttributeHash()).thenReturn(hash);
        return c;
    }

    private EntityModel entity(String entityName, String tableName, List<ColumnModel> cols) {
        EntityModel e = EntityModel.builder()
                .entityName(entityName)
                .tableName(tableName)
                .build();
        // EntityModel.putColumn(...)이 ColumnModel의 tableName/columnName을 사용하므로 mock에 값이 있어야 함
        for (ColumnModel c : cols) {
            e.putColumn(c);
        }
        return e;
    }

    @Test
    void detectsRename_whenKeysMatch_andNamesDiffer() {
        // old: OldE(t_old) -> id(pk,1), name(2)
        EntityModel oldE = entity(
                "OldE", "t_old",
                List.of(
                        col("t_old", "id", true, 1L),
                        col("t_old", "name", false, 2L)
                )
        );
        // new: NewE(t_new) -> ID(pk,1), NAME(2)  // 대소문자만 다름 → normalizer로 같은 키
        EntityModel newE = entity(
                "NewE", "t_new",
                List.of(
                        col("t_new", "ID", true, 1L),
                        col("t_new", "NAME", false, 2L)
                )
        );

        SchemaModel oldSchema = mockSchemaWithEntities(new LinkedHashMap<>(Map.of("OldE", oldE)));
        SchemaModel newSchema = mockSchemaWithEntities(new LinkedHashMap<>(Map.of("NewE", newE)));

        TableDiffer differ = new TableDiffer(CaseNormalizer.lower());
        DiffResult out = DiffResult.builder().build();

        differ.diff(oldSchema, newSchema, out);

        assertTrue(out.getAddedTables().isEmpty(), "No added tables expected");
        assertTrue(out.getDroppedTables().isEmpty(), "No dropped tables expected");
        assertEquals(1, out.getRenamedTables().size(), "One rename expected");
        var rt = out.getRenamedTables().get(0);
        assertEquals("OldE", rt.getOldEntity().getEntityName());
        assertEquals("NewE", rt.getNewEntity().getEntityName());
        assertTrue(rt.getChangeDetail().contains("Table renamed"), "Change detail should mention rename");
    }

    @Test
    void detectsAddedAndDropped_whenNoKeyMatch() {
        // old: A -> id(pk,1)
        EntityModel A = entity("A", "t_a", List.of(col("t_a", "id", true, 1L)));
        // new: B -> id(pk,2)  // 해시가 다름 → 키 불일치
        EntityModel B = entity("B", "t_b", List.of(col("t_b", "id", true, 2L)));

        SchemaModel oldSchema = mockSchemaWithEntities(Map.of("A", A));
        SchemaModel newSchema = mockSchemaWithEntities(Map.of("B", B));

        TableDiffer differ = new TableDiffer();
        DiffResult out = DiffResult.builder().build();

        differ.diff(oldSchema, newSchema, out);

        assertTrue(out.getRenamedTables().isEmpty(), "No rename expected");
        assertEquals(List.of(B), out.getAddedTables(), "B should be added");
        assertEquals(List.of(A), out.getDroppedTables(), "A should be dropped");
    }

    @Test
    void warnsAmbiguousRename_whenMultipleNewCandidatesShareSameKey() {
        // old: A -> id(pk,1), name(2)
        EntityModel A = entity("A", "t_a",
                List.of(col("t_a", "id", true, 1L), col("t_a", "name", false, 2L)));

        // new 쪽에 동일 키를 가진 후보가 2개: B1, B2
        EntityModel B1 = entity("B1", "t_b1",
                List.of(col("t_b1", "ID", true, 1L), col("t_b1", "NAME", false, 2L)));
        EntityModel B2 = entity("B2", "t_b2",
                List.of(col("t_b2", "id", true, 1L), col("t_b2", "name", false, 2L)));

        SchemaModel oldSchema = mockSchemaWithEntities(Map.of("A", A));
        SchemaModel newSchema = mockSchemaWithEntities(new LinkedHashMap<>(Map.of("B1", B1, "B2", B2)));

        TableDiffer differ = new TableDiffer();
        DiffResult out = DiffResult.builder().build();

        differ.diff(oldSchema, newSchema, out);

        // 모호하므로 rename은 생성되지 않음
        assertTrue(out.getRenamedTables().isEmpty(), "No rename due to ambiguity");

        // 경고가 생성되어야 함
        assertTrue(out.getWarnings().stream().anyMatch(w ->
                        w.startsWith("[AMBIGUOUS-RENAME]") && w.contains("A") && w.contains("B1") && w.contains("B2")),
                "Ambiguous rename warning expected");

        // A는 dropped, B1/B2는 added
        assertEquals(
                java.util.Set.of("B1", "B2"),
                out.getAddedTables().stream()
                        .map(org.jinx.model.EntityModel::getEntityName)
                        .collect(java.util.stream.Collectors.toSet()),
                "Both new candidates should be added"
        );
        assertEquals(List.of(A), out.getDroppedTables(), "Old should be dropped");
    }

    @Test
    void handlesNullEntityMaps_gracefully() {
        SchemaModel oldSchema = mock(SchemaModel.class);
        when(oldSchema.getEntities()).thenReturn(null);
        SchemaModel newSchema = mock(SchemaModel.class);
        when(newSchema.getEntities()).thenReturn(null);

        TableDiffer differ = new TableDiffer();
        DiffResult out = DiffResult.builder().build();

        assertDoesNotThrow(() -> differ.diff(oldSchema, newSchema, out));
        assertTrue(out.getAddedTables().isEmpty());
        assertTrue(out.getDroppedTables().isEmpty());
        assertTrue(out.getRenamedTables().isEmpty());
        assertTrue(out.getWarnings().isEmpty());
    }

    @Test
    void keyNormalization_ignoresCaseDifferencesInPkNames() {
        // old: pk "Id"
        EntityModel A = entity("A", "t",
                List.of(col("t", "Id", true, 1L), col("t", "x", false, 2L)));
        // new: pk "id"
        EntityModel B = entity("B", "t2",
                List.of(col("t2", "id", true, 1L), col("t2", "x", false, 2L)));

        SchemaModel oldSchema = mockSchemaWithEntities(Map.of("A", A));
        SchemaModel newSchema = mockSchemaWithEntities(Map.of("B", B));

        TableDiffer differ = new TableDiffer(CaseNormalizer.lower());
        DiffResult out = DiffResult.builder().build();

        differ.diff(oldSchema, newSchema, out);

        assertEquals(1, out.getRenamedTables().size(), "Rename expected with case-insensitive PK");
        assertTrue(out.getAddedTables().isEmpty());
        assertTrue(out.getDroppedTables().isEmpty());
    }
}
