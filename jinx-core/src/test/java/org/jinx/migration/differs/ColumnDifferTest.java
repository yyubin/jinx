package org.jinx.migration.differs;

import jakarta.persistence.FetchType;
import jakarta.persistence.TemporalType;
import org.jinx.descriptor.*;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


class ColumnDifferTest {

    private ColumnDiffer columnDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        columnDiffer = new ColumnDiffer();
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
        oldEntity.setColumns(Map.of("username", col));
        newEntity.setColumns(Map.of("username", col));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getColumnDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 컬럼이 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedColumn() {
        ColumnModel newCol = createColumn("email", "VARCHAR", false);
        oldEntity.setColumns(Collections.emptyMap());
        newEntity.setColumns(Map.of("email", newCol));

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
        oldEntity.setColumns(Map.of("last_login", oldCol));
        newEntity.setColumns(Collections.emptyMap());

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
        oldEntity.setColumns(Map.of("is_active", oldCol));
        newEntity.setColumns(Map.of("is_active", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertNotNull(diff.getChangeDetail());
        assertTrue(diff.getChangeDetail().contains("isNullable changed from false to true"));
    }

    @Test
    @DisplayName("컬럼 이름만 변경되었을 때 'RENAMED'로 감지해야 함")
    void shouldDetectRenamedColumn() {
        ColumnModel oldCol = createColumn("user_name", "VARCHAR", false);
        ColumnModel newCol = createColumn("username", "VARCHAR", false);
        oldEntity.setColumns(Map.of("user_name", oldCol));
        newEntity.setColumns(Map.of("username", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.RENAMED, diff.getType());
        assertEquals("username", diff.getColumn().getColumnName());
        assertEquals("user_name", diff.getOldColumn().getColumnName());
        assertTrue(diff.getChangeDetail().contains("Column renamed from user_name to username"));
    }

    @Test
    @DisplayName("컬럼 이름과 속성이 함께 변경되면 'DROPPED'와 'ADDED'로 감지해야 함")
    void shouldDetectRenamedAndModifiedColumn() {
        ColumnModel oldCol = createColumn("user_name", "VARCHAR", false);
        oldCol.setLength(255);
        ColumnModel newCol = createColumn("username", "VARCHAR", false);
        newCol.setLength(500); // 속성도 함께 변경

        oldEntity.setColumns(Map.of("user_name", oldCol));
        newEntity.setColumns(Map.of("username", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size(), "DROPPED와 ADDED 두 개의 변경이 감지되어야 합니다.");

        boolean droppedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED && d.getColumn().getColumnName().equals("user_name"));
        boolean addedFound = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED && d.getColumn().getColumnName().equals("username"));

        assertTrue(droppedFound, "기존 컬럼이 'DROPPED'로 감지되어야 합니다.");
        assertTrue(addedFound, "새로운 컬럼이 'ADDED'로 감지되어야 합니다.");
    }

    @Test
    @DisplayName("ENUM 매핑 방식이 STRING → ORDINAL 로 바뀌면 MODIFIED와 경고가 발생해야 함")
    void shouldDetectEnumMappingChangeWithWarning() {
        ColumnModel oldCol = createEnumColumn("status", true, "NEW", "DONE");
        ColumnModel newCol = createEnumColumn("status", false, "NEW", "DONE");   // ORDINAL 매핑

        oldEntity.setColumns(Map.of("status", oldCol));
        newEntity.setColumns(Map.of("status", newCol));

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
        ColumnModel newCol = createEnumColumn("grade", false, "SILVER", "BRONZE", "GOLD"); // 순서 교체

        oldEntity.setColumns(Map.of("grade", oldCol));
        newEntity.setColumns(Map.of("grade", newCol));

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
        newCol.setLength(100);      // 감소 → 위험

        oldEntity.setColumns(Map.of("nickname", oldCol));
        newEntity.setColumns(Map.of("nickname", newCol));

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

        oldEntity.setColumns(Map.of("amount", oldCol));
        newEntity.setColumns(Map.of("amount", newCol));

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

        oldEntity.setColumns(Map.of("profile", oldCol));
        newEntity.setColumns(Map.of("profile", newCol));

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

        oldEntity.setColumns(Map.of("meta", oldCol));
        newEntity.setColumns(Map.of("meta", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Converter changed")));
    }

    @Test
    @DisplayName("알 수 없는 타입 변경이면 'verify compatibility' 경고가 발생해야 함")
    void shouldWarnOnUnknownTypeConversion() {
        ColumnModel oldCol = createColumn("amount", "com.foo.OldMoney", false);
        ColumnModel newCol = createColumn("amount", "com.foo.NewMoney", false);

        oldEntity.setColumns(Map.of("amount", oldCol));
        newEntity.setColumns(Map.of("amount", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Type changed from com.foo.OldMoney to com.foo.NewMoney")
                        && w.contains("verify compatibility")));
    }

    @Test
    @DisplayName("컬럼이 rename 되고 defaultValue 만 달라지면 RENAMED 와 MODIFIED 둘 다 나와야 함")
    void shouldDetectRenamedAndValueChanged() {
        ColumnModel oldCol = createColumn("old_code", "VARCHAR", false);
        oldCol.setDefaultValue("A");                 // rename 후보의 기준 속성

        ColumnModel newCol = createColumn("code", "VARCHAR", false);
        newCol.setDefaultValue("B");                 // defaultValue 만 변경

        oldEntity.setColumns(Map.of("old_code", oldCol));
        newEntity.setColumns(Map.of("code", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        // RENAMED 와 MODIFIED 각각 한 개씩
        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());

        boolean renamed = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.RENAMED
                        && d.getChangeDetail().contains("old_code")
                        && d.getChangeDetail().contains("code"));

        boolean modified = modifiedEntityResult.getColumnDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.MODIFIED
                        && d.getChangeDetail().contains("defaultValue changed from A to B")
                        && !d.getChangeDetail().endsWith(";")); // 마지막 세미콜론 제거 확인

        assertTrue(renamed,  "RENAMED diff 가 누락되었습니다");
        assertTrue(modified, "MODIFIED diff 가 누락되었습니다");
    }

    @Test
    @DisplayName("tableName·length·unique 변경 시 detail 문자열에 모두 포함돼야 함")
    void shouldAggregateMultipleChangeDetails() {
        ColumnModel oldCol = createColumn("nickname", "VARCHAR", true);
        oldCol.setTableName("users");
        oldCol.setLength(255);
        oldCol.setUnique(false);

        ColumnModel newCol = createColumn("nickname", "VARCHAR", true);
        newCol.setTableName("members");
        newCol.setLength(100);        // length 줄임
        newCol.setUnique(true);       // unique 추가

        oldEntity.setColumns(Map.of("nickname", oldCol));
        newEntity.setColumns(Map.of("nickname", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        String detail = diff.getChangeDetail();

        assertAll(
                () -> assertTrue(detail.contains("tableName changed from users to members")),
                () -> assertTrue(detail.contains("length changed from 255 to 100")),
                () -> assertTrue(detail.contains("isUnique changed from false to true")),
                () -> assertFalse(detail.endsWith(";"))    // 마지막 ‘; ’ 제거됐는지 확인
        );
    }

    @Test
    @DisplayName("PrimaryKey, precision, scale 변경 시 detail 포함돼야 함")
    void shouldDetectPrimaryKeyPrecisionScaleChange() {
        ColumnModel oldCol = createColumn("score", "DECIMAL", false);
        oldCol.setPrimaryKey(false);
        oldCol.setPrecision(10);
        oldCol.setScale(2);

        ColumnModel newCol = createColumn("score", "DECIMAL", false);
        newCol.setPrimaryKey(true);  // 변경됨
        newCol.setPrecision(12);     // 변경됨
        newCol.setScale(4);          // 변경됨

        oldEntity.setColumns(Map.of("score", oldCol));
        newEntity.setColumns(Map.of("score", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        String detail = modifiedEntityResult.getColumnDiffs().get(0).getChangeDetail();

        assertAll(
                () -> assertTrue(detail.contains("isPrimaryKey changed from false to true")),
                () -> assertTrue(detail.contains("precision changed from 10 to 12")),
                () -> assertTrue(detail.contains("scale changed from 2 to 4"))
        );
    }

    @Test
    @DisplayName("Generation 관련 속성 변경 시 detail 포함돼야 함")
    void shouldDetectGenerationChanges() {
        ColumnModel oldCol = createColumn("id", "LONG", false);
        oldCol.setGenerationStrategy(GenerationStrategy.SEQUENCE);
        oldCol.setSequenceName("old_seq");
        oldCol.setTableGeneratorName("old_gen");

        ColumnModel newCol = createColumn("id", "LONG", false);
        newCol.setGenerationStrategy(GenerationStrategy.IDENTITY);
        newCol.setSequenceName("new_seq");
        newCol.setTableGeneratorName("new_gen");

        oldEntity.setColumns(Map.of("id", oldCol));
        newEntity.setColumns(Map.of("id", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        String detail = modifiedEntityResult.getColumnDiffs().get(0).getChangeDetail();

        assertAll(
                () -> assertTrue(detail.contains("generationStrategy changed")),
                () -> assertTrue(detail.contains("sequenceName changed")),
                () -> assertTrue(detail.contains("tableGeneratorName changed"))
        );
    }

    @Test
    @DisplayName("identity 관련 속성 변경 시 detail 포함돼야 함")
    void shouldDetectIdentityChanges() {
        ColumnModel oldCol = createColumn("id", "LONG", false);
        oldCol.setIdentityStartValue(1);
        oldCol.setIdentityIncrement(1);
        oldCol.setIdentityCache(10);
        oldCol.setIdentityMinValue(1);
        oldCol.setIdentityMaxValue(1000);
        oldCol.setIdentityOptions(new String[] {"CYCLE"});

        ColumnModel newCol = createColumn("id", "LONG", false);
        newCol.setIdentityStartValue(100);
        newCol.setIdentityIncrement(10);
        newCol.setIdentityCache(20);
        newCol.setIdentityMinValue(10);
        newCol.setIdentityMaxValue(9999);
        newCol.setIdentityOptions(new String[] {"NOCYCLE"});

        oldEntity.setColumns(Map.of("id", oldCol));
        newEntity.setColumns(Map.of("id", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        String detail = modifiedEntityResult.getColumnDiffs().get(0).getChangeDetail();

        assertAll(
                () -> assertTrue(detail.contains("identityStartValue changed")),
                () -> assertTrue(detail.contains("identityIncrement changed")),
                () -> assertTrue(detail.contains("identityCache changed")),
                () -> assertTrue(detail.contains("identityMinValue changed")),
                () -> assertTrue(detail.contains("identityMaxValue changed")),
                () -> assertTrue(detail.contains("identityOptions changed"))
        );
    }

    @Test
    @DisplayName("LOB, JDBC, Optional, Version, TemporalType 변경 시 detail 포함돼야 함")
    void shouldDetectLobJdbcOptionalVersionTemporalTypeChanges() {
        ColumnModel oldCol = createColumn("created_at", "TIMESTAMP", false);
        oldCol.setLob(false);
        oldCol.setJdbcType(JdbcType.CLOB);
        oldCol.setOptional(false);
        oldCol.setVersion(false);
        oldCol.setTemporalType(TemporalType.DATE);

        ColumnModel newCol = createColumn("created_at", "TIMESTAMP", false);
        newCol.setLob(true);
        newCol.setJdbcType(JdbcType.BLOB);
        newCol.setOptional(true);
        newCol.setVersion(true);
        newCol.setTemporalType(TemporalType.TIMESTAMP);

        oldEntity.setColumns(Map.of("created_at", oldCol));
        newEntity.setColumns(Map.of("created_at", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        String detail = modifiedEntityResult.getColumnDiffs().get(0).getChangeDetail();

        assertAll(
                () -> assertTrue(detail.contains("isLob changed")),
                () -> assertTrue(detail.contains("jdbcType changed")),
                () -> assertTrue(detail.contains("isOptional changed")),
                () -> assertTrue(detail.contains("isVersion changed")),
                () -> assertTrue(detail.contains("temporalType changed"))
        );
    }

    @Test
    @DisplayName("Enum에 새로운 상수가 추가되면 'added' 경고가 발생해야 함")
    void shouldWarnWhenEnumConstantIsAdded() {
        ColumnModel oldCol = createEnumColumn("status", true, "PENDING", "COMPLETED");
        ColumnModel newCol = createEnumColumn("status", true, "PENDING", "COMPLETED", "CANCELLED"); // CANCELLED 추가

        oldEntity.setColumns(Map.of("status", oldCol));
        newEntity.setColumns(Map.of("status", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Enum constants added") && w.contains("[CANCELLED]")));
        assertFalse(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Enum constants removed")));
    }

    @Test
    @DisplayName("Nullable 컬럼이 Not Null로 변경되면 데이터 손실 경고가 발생해야 함")
    void shouldWarnWhenNullableColumnBecomesNotNull() {
        ColumnModel oldCol = createColumn("description", "VARCHAR", true); // nullable
        ColumnModel newCol = createColumn("description", "VARCHAR", false); // not nullable

        oldEntity.setColumns(Map.of("description", oldCol));
        newEntity.setColumns(Map.of("description", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("is now NOT NULL") && w.contains("existing null data will violate constraint")));
    }

    @Test
    @DisplayName("같은 크기의 숫자 타입 간 변경 시 'verify compatibility' 경고가 발생해야 함")
    void shouldWarnOnSameSizeNumericTypeConversion() {
        ColumnModel oldCol = createColumn("value", "int", false);
        ColumnModel newCol = createColumn("value", "float", false); // int(4) -> float(4)

        oldEntity.setColumns(Map.of("value", oldCol));
        newEntity.setColumns(Map.of("value", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Type changed from int to float") && w.contains("verify compatibility")));
    }

    @Test
    @DisplayName("isManualPrimaryKey 속성 변경 시 MODIFIED로 감지해야 함")
    void shouldDetectManualPrimaryKeyChange() {
        ColumnModel oldCol = createColumn("id", "LONG", false);
        oldCol.setManualPrimaryKey(false);

        ColumnModel newCol = createColumn("id", "LONG", false);
        newCol.setManualPrimaryKey(true); // 변경

        oldEntity.setColumns(Map.of("id", oldCol));
        newEntity.setColumns(Map.of("id", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
    }

    @Test
    @DisplayName("이름 변경과 isManualPrimaryKey 변경은 RENAMED와 MODIFIED로 감지되어야 함")
    void shouldDetectRenameAndModificationForUncheckedAttribute() {
        ColumnModel oldCol = createColumn("old_id", "LONG", false);
        oldCol.setManualPrimaryKey(false);

        ColumnModel newCol = createColumn("new_id", "LONG", false);
        newCol.setManualPrimaryKey(true); // isColumnAttributesEqual에서 확인 안하는 속성

        oldEntity.setColumns(Map.of("old_id", oldCol));
        newEntity.setColumns(Map.of("new_id", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());
        boolean renamed = modifiedEntityResult.getColumnDiffs().stream().anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.RENAMED);
        boolean modified = modifiedEntityResult.getColumnDiffs().stream().anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.MODIFIED);

        assertTrue(renamed, "RENAMED diff가 감지되어야 합니다.");
        assertTrue(modified, "MODIFIED diff가 감지되어야 합니다.");
    }

    @Test
    @DisplayName("이름 변경과 isUnique 변경은 DROPPED와 ADDED로 감지되어야 함")
    void shouldDetectDropAndAddWhenCheckedAttributeChangesOnRename() {
        ColumnModel oldCol = createColumn("old_name", "VARCHAR", false);
        oldCol.setUnique(false);

        ColumnModel newCol = createColumn("new_name", "VARCHAR", false);
        newCol.setUnique(true); // isColumnAttributesEqual에서 확인하는 속성

        oldEntity.setColumns(Map.of("old_name", oldCol));
        newEntity.setColumns(Map.of("new_name", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getColumnDiffs().size());
        boolean dropped = modifiedEntityResult.getColumnDiffs().stream().anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.DROPPED && d.getColumn().getColumnName().equals("old_name"));
        boolean added = modifiedEntityResult.getColumnDiffs().stream().anyMatch(d -> d.getType() == DiffResult.ColumnDiff.Type.ADDED && d.getColumn().getColumnName().equals("new_name"));

        assertTrue(dropped, "DROPPED diff가 감지되어야 합니다.");
        assertTrue(added, "ADDED diff가 감지되어야 합니다.");
    }

    @Test
    @DisplayName("기본값이 non-null에서 null로 변경되면 MODIFIED로 감지해야 함")
    void shouldDetectModificationWhenDefaultValueBecomesNull() {
        ColumnModel oldCol = createColumn("code", "VARCHAR", true);
        oldCol.setDefaultValue("DEFAULT");

        ColumnModel newCol = createColumn("code", "VARCHAR", true);
        newCol.setDefaultValue(null); // null로 변경

        oldEntity.setColumns(Map.of("code", oldCol));
        newEntity.setColumns(Map.of("code", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("defaultValue changed from DEFAULT to null"));
    }

    @Test
    @DisplayName("기본값이 null에서 non-null로 변경되면 MODIFIED로 감지해야 함")
    void shouldDetectModificationWhenDefaultValueBecomesNonNull() {
        ColumnModel oldCol = createColumn("code", "VARCHAR", true);
        oldCol.setDefaultValue(null);

        ColumnModel newCol = createColumn("code", "VARCHAR", true);
        newCol.setDefaultValue("ACTIVE"); // non-null로 변경

        oldEntity.setColumns(Map.of("code", oldCol));
        newEntity.setColumns(Map.of("code", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("defaultValue changed from null to ACTIVE"));
    }

    @Test
    @DisplayName("시퀀스 이름이 null과 non-null 사이를 오갈 때 MODIFIED로 감지해야 함")
    void shouldDetectModificationForSequenceNameNullabilityChange() {
        // Case 1: non-null -> null
        ColumnModel oldCol1 = createColumn("id", "LONG", false);
        oldCol1.setSequenceName("my_seq");
        ColumnModel newCol1 = createColumn("id", "LONG", false);
        newCol1.setSequenceName(null);

        oldEntity.setColumns(Map.of("id", oldCol1));
        newEntity.setColumns(Map.of("id", newCol1));
        DiffResult.ModifiedEntity result1 = DiffResult.ModifiedEntity.builder().build();
        columnDiffer.diff(oldEntity, newEntity, result1);

        assertEquals(1, result1.getColumnDiffs().size(), "non-null -> null 변경 감지 실패");
        assertTrue(result1.getColumnDiffs().get(0).getChangeDetail().contains("sequenceName changed from my_seq to null"), "non-null -> null 변경 디테일 누락");

        // Case 2: null -> non-null
        ColumnModel oldCol2 = createColumn("id", "LONG", false);
        oldCol2.setSequenceName(null);
        ColumnModel newCol2 = createColumn("id", "LONG", false);
        newCol2.setSequenceName("my_seq");

        oldEntity.setColumns(Map.of("id", oldCol2));
        newEntity.setColumns(Map.of("id", newCol2));
        DiffResult.ModifiedEntity result2 = DiffResult.ModifiedEntity.builder().build();
        columnDiffer.diff(oldEntity, newEntity, result2);

        assertEquals(1, result2.getColumnDiffs().size(), "null -> non-null 변경 감지 실패");
        assertTrue(result2.getColumnDiffs().get(0).getChangeDetail().contains("sequenceName changed from null to my_seq"), "null -> non-null 변경 디테일 누락");
    }

    @Test
    @DisplayName("Precision 속성만 다를 경우 MODIFIED로 감지되어야 함")
    void shouldDetectModificationWhenOnlyPrecisionIsDifferent() {
        // isColumnEqual 메서드 내의 short-circuit을 피하고 특정 분기를 테스트하기 위함
        ColumnModel oldCol = createColumn("measurement", "java.math.BigDecimal", false);
        oldCol.setPrecision(10);
        oldCol.setScale(2);
        oldCol.setUnique(true);

        ColumnModel newCol = createColumn("measurement", "java.math.BigDecimal", false);
        newCol.setPrecision(12); // Precision만 변경
        newCol.setScale(2);      // Scale은 동일
        newCol.setUnique(true);  // Unique는 동일

        oldEntity.setColumns(Map.of("measurement", oldCol));
        newEntity.setColumns(Map.of("measurement", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        String detail = diff.getChangeDetail();
        assertTrue(detail.contains("precision changed from 10 to 12"));
        assertFalse(detail.contains("scale changed"), "Precision 외 다른 속성은 변경되지 않아야 합니다.");
    }

    @Test
    @DisplayName("일반 컬럼이 Enum 컬럼으로 변경될 때 MODIFIED로 감지해야 함")
    void shouldDetectChangeFromRegularToEnumColumn() {
        // diff 메서드 내 isEnum() 분기를 테스트하기 위함
        ColumnModel oldCol = createColumn("user_type", "INTEGER", false);
        ColumnModel newCol = createEnumColumn("user_type", false, "USER", "ADMIN"); // ENUM으로 변경

        oldEntity.setColumns(Map.of("user_type", oldCol));
        newEntity.setColumns(Map.of("user_type", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getColumnDiffs().size());
        DiffResult.ColumnDiff diff = modifiedEntityResult.getColumnDiffs().get(0);
        assertEquals(DiffResult.ColumnDiff.Type.MODIFIED, diff.getType());
        assertTrue(diff.getChangeDetail().contains("javaType changed"));
        assertTrue(diff.getChangeDetail().contains("enumValues changed"));
    }

    @Test
    @DisplayName("숫자 타입에서 문자열 타입으로 변경 시 'verify compatibility' 경고가 발생해야 함")
    void shouldWarnOnNumericToStringTypeConversion() {
        // analyzeTypeConversion의 oldSize != null && newSize == null 분기를 테스트하기 위함
        ColumnModel oldCol = createColumn("value", "java.lang.Integer", false);
        ColumnModel newCol = createColumn("value", "java.lang.String", false); // Integer -> String

        oldEntity.setColumns(Map.of("value", oldCol));
        newEntity.setColumns(Map.of("value", newCol));

        columnDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getWarnings().stream()
                .anyMatch(w -> w.contains("Type changed from java.lang.Integer to java.lang.String")
                        && w.contains("verify compatibility")));
    }

    private ColumnModel createColumn(String name, String javaType, boolean isNullable) {
        return ColumnModel.builder()
                .columnName(name)
                .javaType(javaType)
                .isNullable(isNullable)
                .build();
    }

    private ColumnModel createEnumColumn(String name,
                                         boolean enumStringMapping,
                                         String... values) {
        return ColumnModel.builder()
                .columnName(name)
                .javaType("ENUM")
                .enumStringMapping(enumStringMapping)
                .enumValues(values)
                .build();
    }
}
