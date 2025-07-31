package org.jinx.migration.differs;

import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private ColumnModel createColumn(String name, String javaType, boolean isNullable) {
        return ColumnModel.builder()
                .columnName(name)
                .javaType(javaType)
                .isNullable(isNullable)
                .build();
    }
}
