package org.jinx.migration.differs;

import jakarta.persistence.FetchType;
import jakarta.persistence.TemporalType;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SimpleColumnDiffer.
 *
 * SimpleColumnDiffer uses a drop+add approach instead of rename detection.
 * All column name changes are treated as DROP of old column and ADD of new column.
 */
class SimpleColumnDifferTest {

    private SimpleColumnDiffer columnDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        columnDiffer = new SimpleColumnDiffer();
        oldEntity = new EntityModel();
        newEntity = new EntityModel();
        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();
    }

    @Test
    @DisplayName("컬럼 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenColumnsAreIdentical() {
        ColumnModel col = createColumn("username", "VARCHAR", false);
        oldEntity.setColumnFromMap(Map.of("username", col));
        newEntity.setColumnFromMap(Map.of("username", col));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getColumnDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 컬럼이 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedColumn() {
        ColumnModel newCol = createColumn("email", "VARCHAR", false);
        oldEntity.clearColumns();
        newEntity.setColumnFromMap(Map.of("email", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.ADDED, diff.getType());
        assertEquals("email", diff.getColumn().getColumnName());
    }

    @Test
    @DisplayName("기존 컬럼이 삭제되었을 때 'DROPPED'로 감지해야 함")
    void shouldDetectDroppedColumn() {
        ColumnModel oldCol = createColumn("last_login", "TIMESTAMP", true);
        oldEntity.setColumnFromMap(Map.of("last_login", oldCol));
        newEntity.clearColumns();

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.DROPPED, diff.getType());
        assertEquals("last_login", diff.getColumn().getColumnName());
    }

    @Test
    @DisplayName("컬럼 속성이 변경되었을 때 'MODIFIED'로 감지하고 경고를 생성해야 함")
    void shouldDetectModifiedColumn_withWarning() {
        ColumnModel oldCol = createColumn("is_active", "BOOLEAN", false);
        ColumnModel newCol = createColumn("is_active", "BOOLEAN", true); // nullable 변경
        oldEntity.setColumnFromMap(Map.of("is_active", oldCol));
        newEntity.setColumnFromMap(Map.of("is_active", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertNotNull(diff.getChangeDetail());
        assertTrue(diff.getChangeDetail().contains("isNullable changed from false to true"));
    }

    @Test
    @DisplayName("컬럼 이름이 변경되면 DROP+ADD로 처리해야 함 (리네임 탐지 안 함)")
    void shouldDetectDropAndAdd_whenColumnNameChanges() {
        ColumnModel oldCol = createColumn("user_name", "VARCHAR", false);
        ColumnModel newCol = createColumn("username", "VARCHAR", false);
        oldEntity.setColumnFromMap(Map.of("user_name", oldCol));
        newEntity.setColumnFromMap(Map.of("username", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());

        boolean droppedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED &&
                        d.getColumn().getColumnName().equals("user_name"));
        boolean addedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED &&
                        d.getColumn().getColumnName().equals("username"));

        assertTrue(droppedFound, "user_name이 DROPPED로 감지되어야 합니다.");
        assertTrue(addedFound, "username이 ADDED로 감지되어야 합니다.");

        // RENAMED는 없어야 함
        boolean renamedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.RENAMED);
        assertFalse(renamedFound, "RENAMED는 절대 발생하지 않아야 합니다.");
    }

    @Test
    @DisplayName("대소문자만 변경되어도 DROP+ADD로 처리해야 함")
    void shouldDetectDropAndAdd_whenOnlyCaseChanges() {
        ColumnModel oldCol = createColumn("user_email", "VARCHAR", false);
        ColumnModel newCol = createColumn("USER_EMAIL", "VARCHAR", false);
        oldEntity.setColumnFromMap(Map.of("user_email", oldCol));
        newEntity.setColumnFromMap(Map.of("USER_EMAIL", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());

        boolean droppedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED &&
                        d.getColumn().getColumnName().equals("user_email"));
        boolean addedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED &&
                        d.getColumn().getColumnName().equals("USER_EMAIL"));

        assertTrue(droppedFound, "user_email이 DROPPED로 감지되어야 합니다.");
        assertTrue(addedFound, "USER_EMAIL이 ADDED로 감지되어야 합니다.");
    }

    @Test
    @DisplayName("이름과 속성이 모두 변경되면 DROP+ADD로만 처리해야 함")
    void shouldDetectDropAndAdd_whenNameAndAttributesChange() {
        ColumnModel oldCol = createColumn("user_name", "VARCHAR", false);
        oldCol.setLength(255);
        ColumnModel newCol = createColumn("username", "VARCHAR", false);
        newCol.setLength(500);

        oldEntity.setColumnFromMap(Map.of("user_name", oldCol));
        newEntity.setColumnFromMap(Map.of("username", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());

        boolean droppedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED &&
                        d.getColumn().getColumnName().equals("user_name"));
        boolean addedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED &&
                        d.getColumn().getColumnName().equals("username"));

        assertTrue(droppedFound, "user_name이 DROPPED로 감지되어야 합니다.");
        assertTrue(addedFound, "username이 ADDED로 감지되어야 합니다.");

        // RENAMED와 MODIFIED는 없어야 함
        long renamedCount = modifiedEntityResult.getColumnDiffs().stream()
                .filter(d -> d.getType() == DiffResult.ColumnDiff.Type.RENAMED)
                .count();
        long modifiedCount = modifiedEntityResult.getColumnDiffs().stream()
                .filter(d -> d.getType() == DiffResult.ColumnDiff.Type.MODIFIED)
                .count();

        assertEquals(0, renamedCount, "RENAMED는 발생하지 않아야 합니다.");
        assertEquals(0, modifiedCount, "MODIFIED는 발생하지 않아야 합니다 (이름이 다르므로 별개 컬럼으로 간주).");
    }

    @Test
    @DisplayName("속성이 동일해도 이름이 다르면 DROP+ADD로 처리해야 함")
    void shouldDetectDropAndAdd_evenWhenAttributesAreIdentical() {
        ColumnModel oldCol = createColumn("old_email", "VARCHAR", false);
        oldCol.setLength(100);
        oldCol.setNullable(false);
        oldCol.setComment("Email address");

        ColumnModel newCol = createColumn("new_email", "VARCHAR", false);
        newCol.setLength(100);
        newCol.setNullable(false);
        newCol.setComment("Email address");

        oldEntity.setColumnFromMap(Map.of("old_email", oldCol));
        newEntity.setColumnFromMap(Map.of("new_email", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());

        boolean droppedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED &&
                        d.getColumn().getColumnName().equals("old_email"));
        boolean addedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED &&
                        d.getColumn().getColumnName().equals("new_email"));

        assertTrue(droppedFound, "old_email이 DROPPED로 감지되어야 합니다.");
        assertTrue(addedFound, "new_email이 ADDED로 감지되어야 합니다.");
    }

    @Test
    @DisplayName("ENUM 매핑 방식이 STRING → ORDINAL 로 바뀌면 MODIFIED와 경고가 발생해야 함")
    void shouldDetectEnumMappingChangeWithWarning() {
        ColumnModel oldCol = createEnumColumn("status", true, "NEW", "DONE");
        ColumnModel newCol = createEnumColumn("status", false, "NEW", "DONE");

        oldEntity.setColumnFromMap(Map.of("status", oldCol));
        newEntity.setColumnFromMap(Map.of("status", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Enum mapping changed")));
    }

    @Test
    @DisplayName("ORDINAL enum 에서 상수 순서가 바뀌면 경고가 발생해야 함")
    void shouldWarnWhenOrdinalEnumOrderChanges() {
        ColumnModel oldCol = createEnumColumn("grade", false, "BRONZE", "SILVER", "GOLD");
        ColumnModel newCol = createEnumColumn("grade", false, "SILVER", "BRONZE", "GOLD");

        oldEntity.setColumnFromMap(Map.of("grade", oldCol));
        newEntity.setColumnFromMap(Map.of("grade", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Dangerous enum order change")));
    }

    @Test
    @DisplayName("VARCHAR 길이를 줄이면 위험 경고를 발생시켜야 함")
    void shouldWarnOnDangerousLengthReduction() {
        ColumnModel oldCol = createColumn("nickname", "VARCHAR", false);
        oldCol.setLength(255);
        ColumnModel newCol = createColumn("nickname", "VARCHAR", false);
        newCol.setLength(100);

        oldEntity.setColumnFromMap(Map.of("nickname", oldCol));
        newEntity.setColumnFromMap(Map.of("nickname", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Dangerous length reduction")));
    }

    @ParameterizedTest(name = "타입 {0} → {1} 은 {2} 경고를 발생시켜야 함")
    @CsvSource(textBlock = """
        int,long,Safe
        long,int,Dangerous
        float,double,Safe
        double,short,Dangerous
        """)
    void shouldDetectTypeConversionWarnings(String oldType, String newType, String expectedKeyword) {
        ColumnModel oldCol = createColumn("amount", oldType, false);
        ColumnModel newCol = createColumn("amount", newType, false);

        oldEntity.setColumnFromMap(Map.of("amount", oldCol));
        newEntity.setColumnFromMap(Map.of("amount", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains(expectedKeyword)));
    }

    @Test
    @DisplayName("Fetch 전략이 변경되면 경고를 발생시켜야 함")
    void shouldWarnOnFetchTypeChange() {
        ColumnModel oldCol = createColumn("profile", "BLOB", false);
        oldCol.setFetchType(FetchType.LAZY);
        ColumnModel newCol = createColumn("profile", "BLOB", false);
        newCol.setFetchType(FetchType.EAGER);

        oldEntity.setColumnFromMap(Map.of("profile", oldCol));
        newEntity.setColumnFromMap(Map.of("profile", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Fetch strategy changed")));
    }

    @Test
    @DisplayName("Converter(ConversionClass)가 바뀌면 경고를 발생시켜야 함")
    void shouldWarnOnConverterChange() {
        ColumnModel oldCol = createColumn("meta", "JSON", false);
        oldCol.setConversionClass("com.example.JsonConverter");
        ColumnModel newCol = createColumn("meta", "JSON", false);
        newCol.setConversionClass("com.example.NewJsonConverter");

        oldEntity.setColumnFromMap(Map.of("meta", oldCol));
        newEntity.setColumnFromMap(Map.of("meta", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Converter changed")));
    }

    @Test
    @DisplayName("Nullable 컬럼이 Not Null로 변경되면 데이터 손실 경고가 발생해야 함")
    void shouldWarnWhenNullableColumnBecomesNotNull() {
        ColumnModel oldCol = createColumn("description", "VARCHAR", true);
        ColumnModel newCol = createColumn("description", "VARCHAR", false);

        oldEntity.setColumnFromMap(Map.of("description", oldCol));
        newEntity.setColumnFromMap(Map.of("description", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("is now NOT NULL") && w.contains("existing null data will violate constraint")));
    }

    @Test
    @DisplayName("Enum에 새로운 상수가 추가되면 'added' 경고가 발생해야 함")
    void shouldWarnWhenEnumConstantIsAdded() {
        ColumnModel oldCol = createEnumColumn("status", true, "PENDING", "COMPLETED");
        ColumnModel newCol = createEnumColumn("status", true, "PENDING", "COMPLETED", "CANCELLED");

        oldEntity.setColumnFromMap(Map.of("status", oldCol));
        newEntity.setColumnFromMap(Map.of("status", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Enum constants added") && w.contains("[CANCELLED]")));
    }

    @Test
    @DisplayName("Enum 상수가 제거되면 'removed' 경고가 발생해야 함")
    void shouldWarnWhenEnumConstantIsRemoved() {
        ColumnModel oldCol = createEnumColumn("status", true, "PENDING", "COMPLETED", "CANCELLED");
        ColumnModel newCol = createEnumColumn("status", true, "PENDING", "COMPLETED");

        oldEntity.setColumnFromMap(Map.of("status", oldCol));
        newEntity.setColumnFromMap(Map.of("status", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Enum constants removed") && w.contains("[CANCELLED]")));
    }

    @Test
    @DisplayName("복수 컬럼 동시 변경 시 각각 올바르게 감지해야 함")
    void shouldDetectMultipleColumnChanges() {
        // Old: col1, col2, col3
        ColumnModel oldCol1 = createColumn("col1", "VARCHAR", false);
        ColumnModel oldCol2 = createColumn("col2", "INTEGER", false);
        ColumnModel oldCol3 = createColumn("col3", "BOOLEAN", true);

        // New: col2 (modified), col3 (unchanged), col4 (added)
        // col1 dropped
        ColumnModel newCol2 = createColumn("col2", "BIGINT", false); // 타입 변경
        ColumnModel newCol3 = createColumn("col3", "BOOLEAN", true);  // 동일
        ColumnModel newCol4 = createColumn("col4", "DATE", false);    // 추가

        oldEntity.setColumnFromMap(Map.of(
                "col1", oldCol1,
                "col2", oldCol2,
                "col3", oldCol3
        ));
        newEntity.setColumnFromMap(Map.of(
                "col2", newCol2,
                "col3", newCol3,
                "col4", newCol4
        ));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        long droppedCount = modifiedEntityResult.getColumnDiffs().stream()
                .filter(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED)
                .count();
        long modifiedCount = modifiedEntityResult.getColumnDiffs().stream()
                .filter(d -> d.getType() == DiffResult.ColumnDiff.Type.MODIFIED)
                .count();
        long addedCount = modifiedEntityResult.getColumnDiffs().stream()
                .filter(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED)
                .count();

        assertEquals(1, droppedCount, "col1이 DROPPED되어야 합니다.");
        assertEquals(1, modifiedCount, "col2가 MODIFIED되어야 합니다.");
        assertEquals(1, addedCount, "col4가 ADDED되어야 합니다.");
    }

    @Test
    @DisplayName("기본값 변경 시 changeDetail에 포함되어야 함")
    void shouldDetectDefaultValueChange() {
        ColumnModel oldCol = createColumn("status", "VARCHAR", false);
        oldCol.setDefaultValue("ACTIVE");
        ColumnModel newCol = createColumn("status", "VARCHAR", false);
        newCol.setDefaultValue("PENDING");

        oldEntity.setColumnFromMap(Map.of("status", oldCol));
        newEntity.setColumnFromMap(Map.of("status", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertTrue(diff.getChangeDetail().contains("defaultValue changed from ACTIVE to PENDING"));
    }

    @Test
    @DisplayName("Primary Key 속성 변경 시 경고가 발생해야 함")
    void shouldWarnOnPrimaryKeyChange() {
        ColumnModel oldCol = createColumn("id", "BIGINT", false);
        oldCol.setPrimaryKey(false);
        ColumnModel newCol = createColumn("id", "BIGINT", false);
        newCol.setPrimaryKey(true);

        oldEntity.setColumnFromMap(Map.of("id", oldCol));
        newEntity.setColumnFromMap(Map.of("id", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Primary key flag changed")));
    }

    @Test
    @DisplayName("Generation 전략 변경 시 경고가 발생해야 함")
    void shouldWarnOnGenerationStrategyChange() {
        ColumnModel oldCol = createColumn("id", "BIGINT", false);
        oldCol.setGenerationStrategy(GenerationStrategy.SEQUENCE);
        ColumnModel newCol = createColumn("id", "BIGINT", false);
        newCol.setGenerationStrategy(GenerationStrategy.IDENTITY);

        oldEntity.setColumnFromMap(Map.of("id", oldCol));
        newEntity.setColumnFromMap(Map.of("id", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Generation strategy changed")));
    }

    @Test
    @DisplayName("LOB 속성 변경 시 경고가 발생해야 함")
    void shouldWarnOnLobChange() {
        ColumnModel oldCol = createColumn("content", "TEXT", false);
        oldCol.setLob(false);
        ColumnModel newCol = createColumn("content", "TEXT", false);
        newCol.setLob(true);

        oldEntity.setColumnFromMap(Map.of("content", oldCol));
        newEntity.setColumnFromMap(Map.of("content", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("LOB flag changed")));
    }

    @Test
    @DisplayName("Precision/Scale 축소 시 경고가 발생해야 함")
    void shouldWarnOnPrecisionScaleReduction() {
        ColumnModel oldCol = createColumn("price", "DECIMAL", false);
        oldCol.setPrecision(10);
        oldCol.setScale(2);
        ColumnModel newCol = createColumn("price", "DECIMAL", false);
        newCol.setPrecision(8);
        newCol.setScale(1);

        oldEntity.setColumnFromMap(Map.of("price", oldCol));
        newEntity.setColumnFromMap(Map.of("price", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Dangerous precision reduction")));
        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Dangerous scale reduction")));
    }

    @Test
    @DisplayName("TemporalType 변경 시 changeDetail에 포함되어야 함")
    void shouldDetectTemporalTypeChange() {
        ColumnModel oldCol = createColumn("created_at", "TIMESTAMP", false);
        oldCol.setTemporalType(TemporalType.DATE);
        ColumnModel newCol = createColumn("created_at", "TIMESTAMP", false);
        newCol.setTemporalType(TemporalType.TIMESTAMP);

        oldEntity.setColumnFromMap(Map.of("created_at", oldCol));
        newEntity.setColumnFromMap(Map.of("created_at", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertTrue(diff.getChangeDetail().contains("temporalType changed"));
    }

    @Test
    @DisplayName("모든 속성이 변경되어도 이름이 같으면 MODIFIED만 발생해야 함")
    void shouldOnlyDetectModified_whenAllAttributesChangeButNameRemains() {
        ColumnModel oldCol = createColumn("data", "VARCHAR", true);
        oldCol.setLength(100);
        oldCol.setDefaultValue("old");
        oldCol.setComment("old comment");

        ColumnModel newCol = createColumn("data", "INTEGER", false);
        newCol.setLength(0);
        newCol.setDefaultValue("0");
        newCol.setComment("new comment");

        oldEntity.setColumnFromMap(Map.of("data", oldCol));
        newEntity.setColumnFromMap(Map.of("data", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertEquals("data", diff.getColumn().getColumnName());
    }

    private ColumnModel createColumn(String name, String javaType, boolean isNullable) {
        return ColumnModel.builder()
                .columnName(name)
                .javaType(javaType)
                .isNullable(isNullable)
                .build();
    }

    private ColumnModel createEnumColumn(String name, boolean enumStringMapping, String... values) {
        return ColumnModel.builder()
                .columnName(name)
                .javaType("ENUM")
                .enumStringMapping(enumStringMapping)
                .enumValues(values)
                .build();
    }
}
