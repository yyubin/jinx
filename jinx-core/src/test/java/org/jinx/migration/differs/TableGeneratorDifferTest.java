package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.jinx.model.TableGeneratorModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


class TableGeneratorDifferTest {

    private TableGeneratorDiffer tableGeneratorDiffer;

    @BeforeEach
    void setUp() {
        tableGeneratorDiffer = new TableGeneratorDiffer();
    }

    @Test
    @DisplayName("테이블 생성기 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenGeneratorsAreIdentical() {
        TableGeneratorModel tg1 = createTableGenerator("id_gen", "id_table", 1);
        SchemaModel oldSchema = createSchema(tg1);
        SchemaModel newSchema = createSchema(tg1);
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        assertTrue(result.getTableGeneratorDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 테이블 생성기가 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedGenerator() {
        TableGeneratorModel tg1 = createTableGenerator("id_gen", "id_table", 1);
        SchemaModel oldSchema = createSchema(); // Empty schema
        SchemaModel newSchema = createSchema(tg1);
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getTableGeneratorDiffs().size());
        DiffResult.TableGeneratorDiff diff = result.getTableGeneratorDiffs().get(0);
        assertEquals(DiffResult.TableGeneratorDiff.Type.ADDED, diff.getType());
        assertEquals("id_gen", diff.getTableGenerator().getName());
    }

    @Test
    @DisplayName("기존 테이블 생성기가 삭제되었을 때 'DROPPED'로 감지해야 함")
    void shouldDetectDroppedGenerator() {
        TableGeneratorModel tg1 = createTableGenerator("id_gen", "id_table", 1);
        SchemaModel oldSchema = createSchema(tg1);
        SchemaModel newSchema = createSchema(); // Empty schema
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getTableGeneratorDiffs().size());
        DiffResult.TableGeneratorDiff diff = result.getTableGeneratorDiffs().get(0);
        assertEquals(DiffResult.TableGeneratorDiff.Type.DROPPED, diff.getType());
        assertEquals("id_gen", diff.getTableGenerator().getName());
    }

    @Test
    @DisplayName("테이블 생성기 속성이 변경되었을 때 'MODIFIED'로 감지해야 함")
    void shouldDetectModifiedGenerator() {
        TableGeneratorModel oldTg = createTableGenerator("id_gen", "id_table", 1);
        TableGeneratorModel newTg = createTableGenerator("id_gen", "id_table", 100); // initialValue changed
        SchemaModel oldSchema = createSchema(oldTg);
        SchemaModel newSchema = createSchema(newTg);
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getTableGeneratorDiffs().size());
        DiffResult.TableGeneratorDiff diff = result.getTableGeneratorDiffs().get(0);
        assertEquals(DiffResult.TableGeneratorDiff.Type.MODIFIED, diff.getType());
        assertEquals("id_gen", diff.getTableGenerator().getName());
        assertEquals(1, diff.getOldTableGenerator().getInitialValue());
        assertEquals(100, diff.getTableGenerator().getInitialValue());
    }

    @Test
    @DisplayName("table, schema, catalog 변경 시 detail에 모두 포함돼야 함")
    void shouldDetectTableSchemaCatalogChanges() {
        TableGeneratorModel oldTg = createTableGenerator("tg", "tblA", 1);
        oldTg.setSchema("public");
        oldTg.setCatalog("catalogA");

        TableGeneratorModel newTg = createTableGenerator("tg", "tblB", 1);
        newTg.setSchema("app");
        newTg.setCatalog("catalogB");

        SchemaModel oldSchema = createSchema(oldTg);
        SchemaModel newSchema = createSchema(newTg);
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        String detail = result.getTableGeneratorDiffs().get(0).getChangeDetail();
        assertAll(
                () -> assertTrue(detail.contains("table changed from tblA to tblB")),
                () -> assertTrue(detail.contains("schema changed from public to app")),
                () -> assertTrue(detail.contains("catalog changed from catalogA to catalogB"))
        );
    }

    @Test
    @DisplayName("pkColumnName, valueColumnName, pkColumnValue 변경 시 detail 포함돼야 함")
    void shouldDetectPkAndValueColumnChanges() {
        TableGeneratorModel oldTg = createTableGenerator("tg", "table", 1);
        oldTg.setPkColumnName("id");
        oldTg.setValueColumnName("val");
        oldTg.setPkColumnValue("GEN1");

        TableGeneratorModel newTg = createTableGenerator("tg", "table", 1);
        newTg.setPkColumnName("pk_id");
        newTg.setValueColumnName("value");
        newTg.setPkColumnValue("GEN_X");

        SchemaModel oldSchema = createSchema(oldTg);
        SchemaModel newSchema = createSchema(newTg);
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        String detail = result.getTableGeneratorDiffs().get(0).getChangeDetail();
        assertAll(
                () -> assertTrue(detail.contains("pkColumnName changed from id to pk_id")),
                () -> assertTrue(detail.contains("valueColumnName changed from val to value")),
                () -> assertTrue(detail.contains("pkColumnValue changed from GEN1 to GEN_X"))
        );
    }

    @Test
    @DisplayName("allocationSize 변경 시 detail 포함되며 마지막 세미콜론은 제거돼야 함")
    void shouldDetectAllocationSizeChangeAndTrimTrailingSemicolon() {
        TableGeneratorModel oldTg = createTableGenerator("tg", "table", 1);
        oldTg.setAllocationSize(50);

        TableGeneratorModel newTg = createTableGenerator("tg", "table", 1);
        newTg.setAllocationSize(100);  // 변경

        SchemaModel oldSchema = createSchema(oldTg);
        SchemaModel newSchema = createSchema(newTg);
        DiffResult result = DiffResult.builder().build();

        tableGeneratorDiffer.diff(oldSchema, newSchema, result);

        String detail = result.getTableGeneratorDiffs().get(0).getChangeDetail();
        assertAll(
                () -> assertTrue(detail.contains("allocationSize changed from 50 to 100")),
                () -> assertFalse(detail.endsWith(";")),
                () -> assertFalse(detail.endsWith("; "))
        );
    }


    private SchemaModel createSchema(TableGeneratorModel... generators) {
        SchemaModel schema = new SchemaModel();
        if (generators != null) {
            schema.setTableGenerators(Arrays.stream(generators)
                    .collect(Collectors.toMap(TableGeneratorModel::getName, g -> g)));
        } else {
            schema.setTableGenerators(Collections.emptyMap());
        }
        return schema;
    }

    private TableGeneratorModel createTableGenerator(String name, String tableName, int initialValue) {
        TableGeneratorModel tg = TableGeneratorModel.builder().build();
        tg.setName(name);
        tg.setTable(tableName);
        tg.setPkColumnName("pk_col");
        tg.setValueColumnName("val_col");
        tg.setPkColumnValue("pk_val");
        tg.setInitialValue(initialValue);
        tg.setAllocationSize(50);
        return tg;
    }
}
