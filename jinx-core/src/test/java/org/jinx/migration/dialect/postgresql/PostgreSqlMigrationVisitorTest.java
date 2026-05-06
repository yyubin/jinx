package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.AbstractDialect;
import org.jinx.migration.AbstractMigrationVisitor;
import org.jinx.migration.AlterTableBuilder;
import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.alter.*;
import org.jinx.migration.contributor.create.*;
import org.jinx.migration.contributor.drop.*;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSqlMigrationVisitorTest {

    // ── RecordingAlterBuilder: add된 contributor 타입을 기록 ─────────────────

    static class RecordingAlterBuilder extends AlterTableBuilder {
        final List<Class<?>> addedTypes = new ArrayList<>();

        RecordingAlterBuilder(String tableName, DdlDialect dialect) {
            super(tableName, dialect);
        }

        @Override
        public AlterTableBuilder add(DdlContributor unit) {
            addedTypes.add(unit.getClass());
            return this;
        }

        @Override
        public String build() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < addedTypes.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(addedTypes.get(i).getSimpleName());
            }
            return sb.toString();
        }
    }

    // ── 최소 DdlDialect 구현 ──────────────────────────────────────────────────

    static class MinimalDialect extends AbstractDialect {
        @Override protected JavaTypeMapper initializeJavaTypeMapper() {
            return c -> new JavaTypeMapper.JavaType() {
                @Override public String getSqlType(int l, int p, int s) { return "DUMMY"; }
                @Override public boolean needsQuotes() { return true; }
                @Override public String getDefaultValue() { return null; }
            };
        }
        @Override protected ValueTransformer initializeValueTransformer() { return (v, t) -> "'" + v + "'"; }
        @Override public String quoteIdentifier(String raw) { return "\"" + raw + "\""; }
        @Override public org.jinx.migration.spi.visitor.SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity d) { return null; }
        @Override public JavaTypeMapper getJavaTypeMapper() { return javaTypeMapper; }
        @Override public String openCreateTable(String t) { return ""; }
        @Override public String closeCreateTable() { return ""; }
        @Override public String getCreateTableSql(EntityModel e) { return ""; }
        @Override public String getDropTableSql(String t) { return ""; }
        @Override public String getAlterTableSql(DiffResult.ModifiedEntity m) { return ""; }
        @Override public String getRenameTableSql(String o, String n) { return ""; }
        @Override public String getColumnDefinitionSql(ColumnModel c) { return ""; }
        @Override public String getAddColumnSql(String t, ColumnModel c) { return ""; }
        @Override public String getDropColumnSql(String t, ColumnModel c) { return ""; }
        @Override public String getModifyColumnSql(String t, ColumnModel n, ColumnModel o) { return ""; }
        @Override public String getRenameColumnSql(String t, ColumnModel n, ColumnModel o) { return ""; }
        @Override public String getPrimaryKeyDefinitionSql(List<String> pk) { return ""; }
        @Override public String getAddPrimaryKeySql(String t, List<String> pk) { return ""; }
        @Override public String getDropPrimaryKeySql(String t, Collection<ColumnModel> cols) { return ""; }
        @Override public String getConstraintDefinitionSql(ConstraintModel c) { return ""; }
        @Override public String getAddConstraintSql(String t, ConstraintModel c) { return ""; }
        @Override public String getDropConstraintSql(String t, ConstraintModel c) { return ""; }
        @Override public String getModifyConstraintSql(String t, ConstraintModel n, ConstraintModel o) { return ""; }
        @Override public String indexStatement(IndexModel i, String t) { return ""; }
        @Override public String getDropIndexSql(String t, IndexModel i) { return ""; }
        @Override public String getModifyIndexSql(String t, IndexModel n, IndexModel o) { return ""; }
        @Override public String getAddRelationshipSql(String t, RelationshipModel r) { return ""; }
        @Override public String getDropRelationshipSql(String t, RelationshipModel r) { return ""; }
        @Override public String getModifyRelationshipSql(String t, RelationshipModel n, RelationshipModel o) { return ""; }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private DdlDialect dialect;
    private DiffResult.ModifiedEntity diff;
    private ColumnModel idCol;
    private ColumnModel nameCol;

    @BeforeEach
    void setUp() {
        dialect = new MinimalDialect();

        idCol = mock(ColumnModel.class);
        when(idCol.getColumnName()).thenReturn("id");
        when(idCol.isPrimaryKey()).thenReturn(true);

        nameCol = mock(ColumnModel.class);
        when(nameCol.getColumnName()).thenReturn("name");
        when(nameCol.isPrimaryKey()).thenReturn(false);

        EntityModel newEntity = mock(EntityModel.class);
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        cols.put("id", idCol);
        cols.put("name", nameCol);
        when(newEntity.getTableName()).thenReturn("t");
        when(newEntity.getColumns()).thenReturn((Map) cols);

        diff = mock(DiffResult.ModifiedEntity.class);
        when(diff.getNewEntity()).thenReturn(newEntity);
    }

    private PostgreSqlMigrationVisitor newVisitorWithRecordingBuilder()
            throws NoSuchFieldException, IllegalAccessException {
        PostgreSqlMigrationVisitor v = new PostgreSqlMigrationVisitor(diff, dialect);
        Field f = AbstractMigrationVisitor.class.getDeclaredField("alterBuilder");
        f.setAccessible(true);
        f.set(v, new RecordingAlterBuilder("t", dialect));
        return v;
    }

    // ── 테스트 ──────────────────────────────────────────────────────────────

    @Test @DisplayName("테이블 RENAME → TableRenameContributor 추가")
    void visitRenamedTable() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();

        DiffResult.RenamedTable r = mock(DiffResult.RenamedTable.class);
        EntityModel oldE = mock(EntityModel.class), newE = mock(EntityModel.class);
        when(oldE.getTableName()).thenReturn("old_t");
        when(newE.getTableName()).thenReturn("new_t");
        when(r.getOldEntity()).thenReturn(oldE);
        when(r.getNewEntity()).thenReturn(newE);

        v.visitRenamedTable(r);

        assertEquals(List.of(TableRenameContributor.class),
                ((RecordingAlterBuilder) v.getAlterBuilder()).addedTypes);
    }

    @Test @DisplayName("컬럼 ADD/DROP/RENAME(비PK) → 각각 단일 contributor")
    void visitColumn_addDropRename() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        v.visitAddedColumn(nameCol);
        assertEquals(ColumnAddContributor.class, rec.addedTypes.get(0));

        v.visitDroppedColumn(nameCol);
        assertEquals(ColumnDropContributor.class, rec.addedTypes.get(1));

        ColumnModel old = mock(ColumnModel.class);
        when(old.getColumnName()).thenReturn("old_name");
        when(old.isPrimaryKey()).thenReturn(false);
        ColumnModel nw = mock(ColumnModel.class);
        when(nw.getColumnName()).thenReturn("new_name");
        when(nw.isPrimaryKey()).thenReturn(false);

        v.visitRenamedColumn(nw, old);
        assertEquals(ColumnRenameContributor.class, rec.addedTypes.get(2));
        assertEquals(3, rec.addedTypes.size(), "비PK rename은 contributor 하나");
    }

    @Test @DisplayName("비PK 컬럼 MODIFY → ColumnModifyContributor 단일 추가 (PK 처리 없음)")
    void visitModifiedColumn_nonPk() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        ColumnModel newC = mock(ColumnModel.class);
        when(newC.getColumnName()).thenReturn("name");
        when(newC.isPrimaryKey()).thenReturn(false);
        ColumnModel oldC = mock(ColumnModel.class);
        when(oldC.getColumnName()).thenReturn("name");
        when(oldC.isPrimaryKey()).thenReturn(false);

        v.visitModifiedColumn(newC, oldC);

        assertEquals(List.of(ColumnModifyContributor.class), rec.addedTypes);
    }

    @Test @DisplayName("PK 컬럼 MODIFY → PrimaryKeyDropContributor + PrimaryKeyAddContributor + ColumnModifyContributor")
    void visitModifiedColumn_pk_usesPrimaryKeyDropContributor() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        ColumnModel newPk = mock(ColumnModel.class);
        when(newPk.getColumnName()).thenReturn("id");
        when(newPk.isPrimaryKey()).thenReturn(true);
        ColumnModel oldNonPk = mock(ColumnModel.class);
        when(oldNonPk.getColumnName()).thenReturn("id");
        when(oldNonPk.isPrimaryKey()).thenReturn(false);

        v.visitModifiedColumn(newPk, oldNonPk);

        assertEquals(PrimaryKeyDropContributor.class, rec.addedTypes.get(0),
                "PG는 PrimaryKeyDropContributor 사용 (PrimaryKeyComplexDropContributor 아님)");
        assertEquals(PrimaryKeyAddContributor.class, rec.addedTypes.get(1));
        assertEquals(ColumnModifyContributor.class, rec.addedTypes.get(2));
        assertFalse(rec.addedTypes.contains(PrimaryKeyComplexDropContributor.class),
                "PG에서는 MySQL 전용 PrimaryKeyComplexDropContributor 미사용");
    }

    @Test @DisplayName("PK 컬럼 RENAME → PrimaryKeyDropContributor + ColumnRenameContributor + PrimaryKeyAddContributor")
    void visitRenamedColumn_pk() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        ColumnModel oldPk = mock(ColumnModel.class);
        when(oldPk.getColumnName()).thenReturn("id_old");
        when(oldPk.isPrimaryKey()).thenReturn(true);
        ColumnModel newCol = mock(ColumnModel.class);
        when(newCol.getColumnName()).thenReturn("id_new");
        when(newCol.isPrimaryKey()).thenReturn(false);

        v.visitRenamedColumn(newCol, oldPk);

        assertEquals(PrimaryKeyDropContributor.class, rec.addedTypes.get(0));
        assertEquals(ColumnRenameContributor.class, rec.addedTypes.get(1));
        assertEquals(PrimaryKeyAddContributor.class, rec.addedTypes.get(2));
        assertFalse(rec.addedTypes.contains(PrimaryKeyComplexDropContributor.class),
                "PG에서는 PrimaryKeyComplexDropContributor 미사용");
    }

    @Test @DisplayName("PK 추가/삭제/수정 contributor 순서 확인")
    void visitPrimaryKey_variants() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        v.visitAddedPrimaryKey(List.of("id"));
        assertEquals(PrimaryKeyAddContributor.class, rec.addedTypes.get(0));

        v.visitDroppedPrimaryKey();
        assertEquals(PrimaryKeyDropContributor.class, rec.addedTypes.get(1),
                "DROP은 PrimaryKeyDropContributor (Complex 아님)");

        v.visitModifiedPrimaryKey(List.of("id"), List.of("id"));
        assertEquals(PrimaryKeyDropContributor.class, rec.addedTypes.get(2));
        assertEquals(PrimaryKeyAddContributor.class, rec.addedTypes.get(3));
    }

    @Test @DisplayName("인덱스/제약/관계 추가/삭제/수정 contributor 순서 확인")
    void visitIndex_constraint_relationship_variants() throws Exception {
        PostgreSqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        IndexModel idx = mock(IndexModel.class);
        ConstraintModel cs = mock(ConstraintModel.class);
        RelationshipModel rel = mock(RelationshipModel.class);

        v.visitAddedIndex(idx);
        v.visitDroppedIndex(idx);
        v.visitModifiedIndex(idx, idx);
        v.visitAddedConstraint(cs);
        v.visitDroppedConstraint(cs);
        v.visitModifiedConstraint(cs, cs);
        v.visitAddedRelationship(rel);
        v.visitDroppedRelationship(rel);
        v.visitModifiedRelationship(rel, rel);

        assertEquals(List.of(
                IndexAddContributor.class,
                IndexDropContributor.class,
                IndexDropContributor.class, IndexAddContributor.class,
                ConstraintAddContributor.class,
                ConstraintDropContributor.class,
                ConstraintDropContributor.class, ConstraintAddContributor.class,
                RelationshipAddContributor.class,
                RelationshipDropContributor.class,
                RelationshipDropContributor.class, RelationshipAddContributor.class
        ), rec.addedTypes);
    }

    @Test @DisplayName("diff가 null이면 NPE 없이 빈 SQL 반환")
    void diffNull_noNpe() {
        PostgreSqlMigrationVisitor v = new PostgreSqlMigrationVisitor(
                (DiffResult.ModifiedEntity) null, dialect);
        assertDoesNotThrow(() -> assertEquals("", v.getGeneratedSql()));
    }

    @Test @DisplayName("diff가 있고 alter 없으면 빈 SQL 반환")
    void diffPresent_noAlter_empty() {
        EntityModel entity = EntityModel.builder().tableName("animals").build();
        DiffResult.ModifiedEntity d = DiffResult.ModifiedEntity.builder().newEntity(entity).build();
        PostgreSqlMigrationVisitor v = new PostgreSqlMigrationVisitor(d, dialect);
        assertDoesNotThrow(() -> assertEquals("", v.getGeneratedSql()));
    }
}
