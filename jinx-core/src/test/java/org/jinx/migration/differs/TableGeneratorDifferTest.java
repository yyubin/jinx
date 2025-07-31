package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.jinx.model.TableGeneratorModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
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
