package org.jinx.migration.dialect.mysql;

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
import org.jinx.migration.dialect.mysql.MySqlMigrationVisitor;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySqlMigrationVisitorTest {

    private DdlDialect dialect;
    private DiffResult.ModifiedEntity diff;
    private EntityModel newEntity;
    private ColumnModel idCol;
    private ColumnModel nameCol;

    // ---- 테스트 전용 AlterBuilder: add된 Contributor의 클래스명을 기록하고 build()에서 조인해 반환 ----
    static class RecordingAlterBuilder extends AlterTableBuilder {
        final List<Class<?>> addedTypes = new ArrayList<>();
        RecordingAlterBuilder(String tableName, DdlDialect dialect) {
            super(tableName, dialect);
        }
        @Override
        public AlterTableBuilder add(DdlContributor unit) {
            addedTypes.add(unit.getClass());
            // super.add(unit) 호출해도 되지만, 실제 contribute 실행을 피하려면 생략 가능.
            return this;
        }
        @Override
        public String build() {
            // 테스트 편의상 타입명만 줄바꿈으로 연결해 반환
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < addedTypes.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(addedTypes.get(i).getSimpleName());
            }
            return sb.toString();
        }
    }

    // 간단한 DdlDialect (실제 실행 경로에선 사용 안 함)
    static class MinimalDialect extends AbstractDialect implements DdlDialect {
        @Override protected JavaTypeMapper initializeJavaTypeMapper() {
            return clazz -> new JavaTypeMapper.JavaType() {
                @Override public String getSqlType(int length, int precision, int scale) { return "DUMMY"; }
                @Override public boolean needsQuotes() { return true; }
                @Override public String getDefaultValue() { return "NULL"; }
            };
        }
        @Override protected ValueTransformer initializeValueTransformer() { return (v,t)-> t.needsQuotes() ? "'" + v + "'" : v; }
        @Override public String quoteIdentifier(String raw) { return "\"" + raw + "\""; }
        @Override public org.jinx.migration.spi.visitor.SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff) { return null; }
        @Override public JavaTypeMapper getJavaTypeMapper() { return this.javaTypeMapper; }
        // DdlDialect 나머지 메서드는 테스트에서 호출되지 않으므로 생략 가능하지만, 필요시 더미로 구현 가능
        @Override public String openCreateTable(String tableName) { return ""; }
        @Override public String closeCreateTable() { return ""; }
        @Override public String getCreateTableSql(EntityModel entity) { return ""; }
        @Override public String getDropTableSql(String tableName) { return ""; }
        @Override public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) { return ""; }
        @Override public String getRenameTableSql(String oldTableName, String newTableName) { return ""; }
        @Override public String getColumnDefinitionSql(org.jinx.model.ColumnModel column) { return ""; }
        @Override public String getAddColumnSql(String table, org.jinx.model.ColumnModel column) { return ""; }
        @Override public String getDropColumnSql(String table, org.jinx.model.ColumnModel column) { return ""; }
        @Override public String getModifyColumnSql(String table, org.jinx.model.ColumnModel newColumn, org.jinx.model.ColumnModel oldColumn) { return ""; }
        @Override public String getRenameColumnSql(String table, org.jinx.model.ColumnModel newColumn, org.jinx.model.ColumnModel oldColumn) { return ""; }
        @Override public String getPrimaryKeyDefinitionSql(List<String> pkColumns) { return ""; }
        @Override public String getAddPrimaryKeySql(String table, List<String> pkColumns) { return ""; }
        @Override public String getDropPrimaryKeySql(String table, Collection<org.jinx.model.ColumnModel> currentColumns) { return ""; }
        @Override public String getConstraintDefinitionSql(org.jinx.model.ConstraintModel constraint) { return ""; }
        @Override public String getAddConstraintSql(String table, org.jinx.model.ConstraintModel constraint) { return ""; }
        @Override public String getDropConstraintSql(String table, org.jinx.model.ConstraintModel constraint) { return ""; }
        @Override public String getModifyConstraintSql(String table, org.jinx.model.ConstraintModel newConstraint, org.jinx.model.ConstraintModel oldConstraint) { return ""; }
        @Override public String indexStatement(org.jinx.model.IndexModel idx, String table) { return ""; }
        @Override public String getDropIndexSql(String table, org.jinx.model.IndexModel index) { return ""; }
        @Override public String getModifyIndexSql(String table, org.jinx.model.IndexModel newIndex, org.jinx.model.IndexModel oldIndex) { return ""; }
        @Override public String getAddRelationshipSql(String table, org.jinx.model.RelationshipModel rel) { return ""; }
        @Override public String getDropRelationshipSql(String table, org.jinx.model.RelationshipModel rel) { return ""; }
        @Override public String getModifyRelationshipSql(String table, org.jinx.model.RelationshipModel newRel, org.jinx.model.RelationshipModel oldRel) { return ""; }
    }

    @BeforeEach
    void setUp() {
        dialect = new MinimalDialect();

        // 컬럼 목킹: id(pk), name(non-pk)
        idCol = mock(ColumnModel.class);
        when(idCol.getColumnName()).thenReturn("id");
        when(idCol.isPrimaryKey()).thenReturn(true);

        nameCol = mock(ColumnModel.class);
        when(nameCol.getColumnName()).thenReturn("name");
        when(nameCol.isPrimaryKey()).thenReturn(false);

        // newEntity 컬럼 맵
        newEntity = mock(EntityModel.class);
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        cols.put("id", idCol);
        cols.put("name", nameCol);
        when(newEntity.getTableName()).thenReturn("t");
        when(newEntity.getColumns()).thenReturn((Map) cols);

        // diff
        diff = mock(DiffResult.ModifiedEntity.class);
        when(diff.getNewEntity()).thenReturn(newEntity);
    }

    private MySqlMigrationVisitor newVisitorWithRecordingBuilder() throws NoSuchFieldException, IllegalAccessException {
        MySqlMigrationVisitor v = new MySqlMigrationVisitor(diff, dialect);
        // alterBuilder 교체 (같은 패키지이므로 protected 접근 가능)
        Field f = AbstractMigrationVisitor.class.getDeclaredField("alterBuilder");
        f.setAccessible(true);
        f.set(v, new RecordingAlterBuilder("t", dialect));
        return v;
    }

    @Test
    @DisplayName("테이블 이름 변경 시 TableRenameContributor가 추가된다")
    void visitRenamedTable_adds_rename_contributor() throws NoSuchFieldException, IllegalAccessException {
        MySqlMigrationVisitor v = newVisitorWithRecordingBuilder();

        // RenamedTable: getOldEntity()/getNewEntity() -> EntityModel.getTableName()
        DiffResult.RenamedTable r = mock(DiffResult.RenamedTable.class);
        EntityModel oldE = mock(EntityModel.class);
        EntityModel newE = mock(EntityModel.class);
        when(oldE.getTableName()).thenReturn("old_t");
        when(newE.getTableName()).thenReturn("new_t");
        when(r.getOldEntity()).thenReturn(oldE);
        when(r.getNewEntity()).thenReturn(newE);

        v.visitRenamedTable(r);

        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();
        assertEquals(List.of(TableRenameContributor.class), rec.addedTypes);
    }

    @Test
    @DisplayName("컬럼 추가/삭제/수정/이름변경에 알맞은 Contributor가 추가된다")
    void visitColumn_variants_add_expected_contributors() throws NoSuchFieldException, IllegalAccessException {
        MySqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        // ADD
        v.visitAddedColumn(nameCol);
        assertEquals(ColumnAddContributor.class, rec.addedTypes.get(0));

        // DROP
        v.visitDroppedColumn(nameCol);
        assertEquals(ColumnDropContributor.class, rec.addedTypes.get(1));

        // MODIFY (pk 관여 X): new(pk=false), old(pk=false)
        ColumnModel oldNonPk = mock(ColumnModel.class);
        when(oldNonPk.getColumnName()).thenReturn("name_old");
        when(oldNonPk.isPrimaryKey()).thenReturn(false);

        v.visitModifiedColumn(nameCol, oldNonPk);
        assertEquals(ColumnModifyContributor.class, rec.addedTypes.get(2));

        // MODIFY (pk 관여 O): new(pk=true) -> dropPK, addPK, modify
        ColumnModel newPk = mock(ColumnModel.class);
        when(newPk.getColumnName()).thenReturn("id");
        when(newPk.isPrimaryKey()).thenReturn(true);

        v.visitModifiedColumn(newPk, oldNonPk);
        assertEquals(PrimaryKeyComplexDropContributor.class, rec.addedTypes.get(3));
        assertEquals(PrimaryKeyAddContributor.class,           rec.addedTypes.get(4));
        assertEquals(ColumnModifyContributor.class,            rec.addedTypes.get(5));

        // RENAME (old가 pk O): dropPK, rename, addPK
        ColumnModel oldPk = mock(ColumnModel.class);
        when(oldPk.getColumnName()).thenReturn("id_old");
        when(oldPk.isPrimaryKey()).thenReturn(true);
        ColumnModel newForRename = mock(ColumnModel.class);
        when(newForRename.getColumnName()).thenReturn("id_new");
        when(newForRename.isPrimaryKey()).thenReturn(false); // 신컬럼 pk 여부는 무관

        v.visitRenamedColumn(newForRename, oldPk);
        assertEquals(PrimaryKeyComplexDropContributor.class, rec.addedTypes.get(6));
        assertEquals(ColumnRenameContributor.class,          rec.addedTypes.get(7));
        assertEquals(PrimaryKeyAddContributor.class,         rec.addedTypes.get(8));

        // RENAME (old가 pk X): rename만
        ColumnModel oldNonPk2 = mock(ColumnModel.class);
        when(oldNonPk2.getColumnName()).thenReturn("old_name");
        when(oldNonPk2.isPrimaryKey()).thenReturn(false);
        ColumnModel newNonPk2 = mock(ColumnModel.class);
        when(newNonPk2.getColumnName()).thenReturn("new_name");
        when(newNonPk2.isPrimaryKey()).thenReturn(false);

        v.visitRenamedColumn(newNonPk2, oldNonPk2);
        assertEquals(ColumnRenameContributor.class, rec.addedTypes.get(9));
    }

    @Test
    @DisplayName("PK 추가/삭제/수정 컨트리뷰터가 순서대로 추가된다")
    void visitPrimaryKey_variants() throws NoSuchFieldException, IllegalAccessException {
        MySqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        // added
        v.visitAddedPrimaryKey(List.of("id"));
        assertEquals(PrimaryKeyAddContributor.class, rec.addedTypes.get(0));

        // dropped
        v.visitDroppedPrimaryKey();
        assertEquals(PrimaryKeyComplexDropContributor.class, rec.addedTypes.get(1));

        // modified -> drop, add
        v.visitModifiedPrimaryKey(List.of("id"), List.of("id"));
        assertEquals(PrimaryKeyComplexDropContributor.class, rec.addedTypes.get(2));
        assertEquals(PrimaryKeyAddContributor.class,         rec.addedTypes.get(3));
    }

    @Test
    @DisplayName("인덱스/제약/관계 추가/삭제/수정 컨트리뷰터가 올바르게 추가된다")
    void visitIndex_constraint_relationship_variants() throws NoSuchFieldException, IllegalAccessException {
        MySqlMigrationVisitor v = newVisitorWithRecordingBuilder();
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

        List<Class<?>> expected = List.of(
                IndexAddContributor.class,
                IndexDropContributor.class,
                IndexModifyContributor.class,
                ConstraintAddContributor.class,
                ConstraintDropContributor.class,
                ConstraintModifyContributor.class,
                RelationshipAddContributor.class,
                RelationshipDropContributor.class,
                RelationshipModifyContributor.class
        );
        assertEquals(expected, rec.addedTypes);
    }

    @Test
    @DisplayName("getGeneratedSql은 RecordingAlterBuilder의 build 결과를 반환한다")
    void getGeneratedSql_returns_built_sequence() throws NoSuchFieldException, IllegalAccessException {
        MySqlMigrationVisitor v = newVisitorWithRecordingBuilder();
        RecordingAlterBuilder rec = (RecordingAlterBuilder) v.getAlterBuilder();

        v.visitAddedColumn(nameCol);
        v.visitDroppedPrimaryKey();
        v.visitAddedPrimaryKey(List.of("id"));

        String sql = v.getGeneratedSql();
        // build()는 타입명을 줄바꿈으로 연결함
        assertEquals(String.join("\n",
                ColumnAddContributor.class.getSimpleName(),
                PrimaryKeyComplexDropContributor.class.getSimpleName(),
                PrimaryKeyAddContributor.class.getSimpleName()
        ), sql);
    }

    @Test
    void getGeneratedSql_diffNull_returnsEmptyAndNoNpe() {
        DdlDialect dialect = mock(DdlDialect.class);
        MySqlMigrationVisitor visitor = new MySqlMigrationVisitor(null, dialect);
        assertDoesNotThrow(() -> {
            String sql = visitor.getGeneratedSql();
            assertEquals("", sql);
        });
    }

    @Test
    void getGeneratedSql_diffPresentButNoAlter_noNpeAndEmptyAlter() {
        DdlDialect dialect = mock(DdlDialect.class);

        EntityModel newEntity = EntityModel.builder().tableName("animals").build();
        DiffResult.ModifiedEntity diff = DiffResult.ModifiedEntity.builder()
                .newEntity(newEntity)
                .build();

        MySqlMigrationVisitor visitor = new MySqlMigrationVisitor(diff, dialect);

        assertDoesNotThrow(() -> {
            String sql = visitor.getGeneratedSql();
            assertEquals("", sql);
        });
    }
}
