package org.jinx.migration.differs;

import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ColumnDifferPrivateMethodsTest {

    private ColumnDiffer columnDiffer;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    private Method getColumnChangeDetail;
    private Method analyzeColumnChanges;
    private Method analyzeEnumChanges;
    private Method analyzeTypeConversion;

    @BeforeEach
    void setUp() throws Exception {
        columnDiffer = new ColumnDiffer();
        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
                .oldEntity(new EntityModel())
                .newEntity(new EntityModel())
                .build();

        getColumnChangeDetail = ColumnDiffer.class.getDeclaredMethod("getColumnChangeDetail", ColumnModel.class, ColumnModel.class);
        getColumnChangeDetail.setAccessible(true);

        analyzeColumnChanges = ColumnDiffer.class.getDeclaredMethod("analyzeColumnChanges", ColumnModel.class, ColumnModel.class, DiffResult.ModifiedEntity.class);
        analyzeColumnChanges.setAccessible(true);

        analyzeEnumChanges = ColumnDiffer.class.getDeclaredMethod("analyzeEnumChanges", ColumnModel.class, ColumnModel.class, DiffResult.ModifiedEntity.class);
        analyzeEnumChanges.setAccessible(true);

        analyzeTypeConversion = ColumnDiffer.class.getDeclaredMethod("analyzeTypeConversion", String.class, String.class);
        analyzeTypeConversion.setAccessible(true);
    }

    @Test
    @DisplayName("[getColumnChangeDetail] 컬럼 타입 변경 시 상세 내역을 정확히 생성해야 함")
    void getColumnChangeDetail_shouldDetailTypeChange() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().javaType("String").build();
        ColumnModel newCol = ColumnModel.builder().javaType("Integer").build();

        String detail = (String) getColumnChangeDetail.invoke(columnDiffer, oldCol, newCol);

        assertEquals("javaType changed from String to Integer", detail);
    }

    @Test
    @DisplayName("[getColumnChangeDetail] Nullable 속성 변경 시 상세 내역을 정확히 생성해야 함")
    void getColumnChangeDetail_shouldDetailNullableChange() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().isNullable(true).build();
        ColumnModel newCol = ColumnModel.builder().isNullable(false).build();

        String detail = (String) getColumnChangeDetail.invoke(columnDiffer, oldCol, newCol);

        assertEquals("isNullable changed from true to false", detail);
    }

    @Test
    @DisplayName("[getColumnChangeDetail] 여러 속성 변경 시 모든 내역을 포함해야 함")
    void getColumnChangeDetail_shouldDetailMultipleChanges() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().length(255).build();
        ColumnModel newCol = ColumnModel.builder().length(500).build();

        String detail = (String) getColumnChangeDetail.invoke(columnDiffer, oldCol, newCol);

        assertTrue(detail.contains("length changed from 255 to 500"));
    }

    @Test
    @DisplayName("[analyzeColumnChanges] Nullable 컬럼이 Not Null로 변경 시 위험 경고를 추가해야 함")
    void analyzeColumnChanges_shouldWarnOnNullableToNotNull() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().columnName("email").isNullable(true).build();
        ColumnModel newCol = ColumnModel.builder().columnName("email").isNullable(false).build();

        analyzeColumnChanges.invoke(columnDiffer, oldCol, newCol, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getWarnings().size());
        assertTrue(modifiedEntityResult.getWarnings().get(0).contains("is now NOT NULL"));
    }

    @Test
    @DisplayName("[analyzeColumnChanges] 데이터 타입 확장(Widening) 시 안전 경고를 추가해야 함")
    void analyzeColumnChanges_shouldWarnOnWideningConversion() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().columnName("amount").javaType("int").build();
        ColumnModel newCol = ColumnModel.builder().columnName("amount").javaType("long").build();

        analyzeColumnChanges.invoke(columnDiffer, oldCol, newCol, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getWarnings().size());
        assertTrue(modifiedEntityResult.getWarnings().get(0).contains("Safe type conversion"));
    }

    @Test
    @DisplayName("[analyzeColumnChanges] 데이터 타입 축소(Narrowing) 시 위험 경고를 추가해야 함")
    void analyzeColumnChanges_shouldWarnOnNarrowingConversion() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().columnName("amount").javaType("long").build();
        ColumnModel newCol = ColumnModel.builder().columnName("amount").javaType("int").build();

        analyzeColumnChanges.invoke(columnDiffer, oldCol, newCol, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getWarnings().size());
        assertTrue(modifiedEntityResult.getWarnings().get(0).contains("Dangerous type conversion"));
    }

    @Test
    @DisplayName("[analyzeColumnChanges] 컬럼 길이 축소 시 위험 경고를 추가해야 함")
    void analyzeColumnChanges_shouldWarnOnLengthReduction() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().columnName("title").length(255).build();
        ColumnModel newCol = ColumnModel.builder().columnName("title").length(100).build();

        analyzeColumnChanges.invoke(columnDiffer, oldCol, newCol, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getWarnings().size());
        assertTrue(modifiedEntityResult.getWarnings().get(0).contains("Dangerous length reduction"));
    }

    @Test
    @DisplayName("[analyzeEnumChanges] Enum 상수가 삭제될 때 경고를 추가해야 함")
    void analyzeEnumChanges_shouldWarnOnRemovedEnumConstants() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().columnName("status").enumValues(new String[]{"ACTIVE", "INACTIVE", "DELETED"}).build();
        ColumnModel newCol = ColumnModel.builder().columnName("status").enumValues(new String[]{"ACTIVE", "INACTIVE"}).build();

        analyzeEnumChanges.invoke(columnDiffer, oldCol, newCol, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertEquals(1, warnings.size(), "하나의 경고만 생성되어야 합니다.");

        String expectedWarningSubstring = "Enum constants removed in column status: [DELETED]";
        assertTrue(
                warnings.get(0).contains(expectedWarningSubstring),
                "경고 메시지에 삭제된 Enum 상수 정보가 포함되어야 합니다.\n" +
                        "Expected to contain: <" + expectedWarningSubstring + ">\n" +
                        "Actual warning was : <" + warnings.get(0) + ">"
        );
    }

    @Test
    @DisplayName("[analyzeEnumChanges] Ordinal Enum의 순서가 변경될 때 위험 경고를 추가해야 함")
    void analyzeEnumChanges_shouldWarnOnOrdinalOrderChange() throws Exception {
        ColumnModel oldCol = ColumnModel.builder().columnName("priority").enumValues(new String[]{"LOW", "MEDIUM", "HIGH"}).enumStringMapping(false).build();
        ColumnModel newCol = ColumnModel.builder().columnName("priority").enumValues(new String[]{"MEDIUM", "LOW", "HIGH"}).enumStringMapping(false).build();

        analyzeEnumChanges.invoke(columnDiffer, oldCol, newCol, modifiedEntityResult);

        List<String> warnings = modifiedEntityResult.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Dangerous enum order change")));
    }

    @Test
    @DisplayName("[diff] Enum 매핑 방식이 변경될 때 MODIFIED와 경고를 생성해야 함")
    void diff_shouldCreateDiffAndWarning_whenEnumMappingChanges() {
        ColumnModel oldCol = ColumnModel.builder().columnName("status").enumValues(new String[]{"A", "B"}).enumStringMapping(false).build(); // ORDINAL
        ColumnModel newCol = ColumnModel.builder().columnName("status").enumValues(new String[]{"A", "B"}).enumStringMapping(true).build();  // STRING

        EntityModel oldEntity = new EntityModel();
        oldEntity.setColumns(Map.of("status", oldCol));
        EntityModel newEntity = new EntityModel();
        newEntity.setColumns(Map.of("status", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size(), "하나의 MODIFIED diff가 생성되어야 합니다.");
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, modifiedEntityResult.getColumnDiffs().get(0).getType());
        assertTrue(modifiedEntityResult.getColumnDiffs().get(0).getChangeDetail().contains("Enum mapping changed"));

        assertEquals(1, modifiedEntityResult.getWarnings().size(), "하나의 경고가 생성되어야 합니다.");
        assertTrue(modifiedEntityResult.getWarnings().get(0).contains("Enum mapping changed on column status"));
    }

    @Test
    @DisplayName("[analyzeTypeConversion] 타입 축소(Narrowing)를 정확히 식별해야 함")
    void analyzeTypeConversion_shouldIdentifyNarrowing() throws Exception {
        String result = (String) analyzeTypeConversion.invoke(columnDiffer, "long", "int");
        assertTrue(result.startsWith("Narrowing conversion"));
    }

    @Test
    @DisplayName("[analyzeTypeConversion] 타입 확장(Widening)을 정확히 식별해야 함")
    void analyzeTypeConversion_shouldIdentifyWidening() throws Exception {
        String result = (String) analyzeTypeConversion.invoke(columnDiffer, "short", "double");
        assertTrue(result.startsWith("Widening conversion"));
    }
}
