package org.jinx.migration;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.RelationshipModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbstractMigrationVisitorTest {

    // ---- 테스트용 최소 DdlDialect 구현 (이전 테스트들과 동일한 스타일) ----
    static class MinimalDialect extends AbstractDialect implements DdlDialect {
        @Override protected JavaTypeMapper initializeJavaTypeMapper() {
            return clazz -> new JavaTypeMapper.JavaType() {
                @Override public String getSqlType(int length, int precision, int scale) { return "DUMMY"; }
                @Override public boolean needsQuotes() { return true; }
                @Override public String getDefaultValue() { return "NULL"; }
            };
        }
        @Override protected ValueTransformer initializeValueTransformer() {
            return (v,t) -> t.needsQuotes() ? "'" + v + "'" : v;
        }
        @Override public String quoteIdentifier(String raw) { return "\"" + raw + "\""; }
        @Override public org.jinx.migration.spi.visitor.SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff) { return null; }
        @Override public JavaTypeMapper getJavaTypeMapper() { return this.javaTypeMapper; }

        // Table
        @Override public String openCreateTable(String tableName) { return "CREATE TABLE " + quoteIdentifier(tableName) + " ("; }
        @Override public String closeCreateTable() { return ")"; }
        @Override public String getCreateTableSql(EntityModel entity) { return "CREATE TABLE " + quoteIdentifier(entity.getTableName()) + " ()"; }
        @Override public String getDropTableSql(String tableName) { return "DROP TABLE " + quoteIdentifier(tableName); }
        @Override public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) { return "/* ALTER TABLE stub */"; }
        @Override public String getRenameTableSql(String oldTableName, String newTableName) { return "RENAME TABLE " + quoteIdentifier(oldTableName) + " TO " + quoteIdentifier(newTableName); }
        // Column
        @Override public String getColumnDefinitionSql(ColumnModel column) { return quoteIdentifier(column.getColumnName()) + " DUMMY"; }
        @Override public String getAddColumnSql(String table, ColumnModel column) { return "ALTER TABLE " + quoteIdentifier(table) + " ADD COLUMN " + getColumnDefinitionSql(column); }
        @Override public String getDropColumnSql(String table, ColumnModel column) { return "ALTER TABLE " + quoteIdentifier(table) + " DROP COLUMN " + quoteIdentifier(column.getColumnName()); }
        @Override public String getModifyColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn) {
            return "ALTER TABLE " + quoteIdentifier(table) + " MODIFY COLUMN " + quoteIdentifier(newColumn.getColumnName()) + " /* from " + quoteIdentifier(oldColumn.getColumnName()) + " */";
        }
        @Override public String getRenameColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn) {
            return "ALTER TABLE " + quoteIdentifier(table) + " RENAME COLUMN " + quoteIdentifier(oldColumn.getColumnName()) + " TO " + quoteIdentifier(newColumn.getColumnName());
        }
        // PK
        @Override public String getPrimaryKeyDefinitionSql(List<String> pkColumns) {
            String cols = String.join(", ", pkColumns.stream().map(this::quoteIdentifier).toList());
            return "PRIMARY KEY (" + cols + ")";
        }
        @Override public String getAddPrimaryKeySql(String table, List<String> pkColumns) {
            String cols = String.join(", ", pkColumns.stream().map(this::quoteIdentifier).toList());
            return "ALTER TABLE " + quoteIdentifier(table) + " ADD PRIMARY KEY (" + cols + ")";
        }
        @Override public String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns) {
            return "ALTER TABLE " + quoteIdentifier(table) + " DROP PRIMARY KEY";
        }
        // Constraints/Indexes/Relationships (미사용 경로) — 더미
        @Override public String getConstraintDefinitionSql(ConstraintModel constraint) { return "/* constraint stub */"; }
        @Override public String getAddConstraintSql(String table, ConstraintModel constraint) { return "/* add constraint stub */"; }
        @Override public String getDropConstraintSql(String table, ConstraintModel constraint) { return "/* drop constraint stub */"; }
        @Override public String getModifyConstraintSql(String table, ConstraintModel newConstraint, ConstraintModel oldConstraint) { return "/* modify constraint stub */"; }
        @Override public String indexStatement(IndexModel idx, String table) { return "/* index stub */"; }
        @Override public String getDropIndexSql(String table, IndexModel index) { return "/* drop index stub */"; }
        @Override public String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex) { return "/* modify index stub */"; }
        @Override public String getAddRelationshipSql(String table, org.jinx.model.RelationshipModel rel) { return "/* add rel stub */"; }
        @Override public String getDropRelationshipSql(String table, org.jinx.model.RelationshipModel rel) { return "/* drop rel stub */"; }
        @Override public String getModifyRelationshipSql(String table, org.jinx.model.RelationshipModel newRel, org.jinx.model.RelationshipModel oldRel) { return "/* modify rel stub */"; }
    }

    // ---- 테스트용 구체 Visitor: AbstractMigrationVisitor를 그대로 사용하면서 alterBuilder 제어 가능 ----
    static class TestMigrationVisitor extends AbstractMigrationVisitor {
        TestMigrationVisitor(DdlDialect ddl, DiffResult.ModifiedEntity diff) {
            super(ddl, diff);
        }
    }

    @Test
    @DisplayName("visitAddedTable은 CREATE TABLE 스켈레톤을 SQL에 추가한다")
    void visitAddedTable_adds_create_sql() {
        DdlDialect dialect = new MinimalDialect();
        EntityModel table = mock(EntityModel.class);
        when(table.getTableName()).thenReturn("users");
        // 컬렉션들이 null이면 NPE 가능 — 최소한 빈 맵/리스트가 나오도록 목킹(필요 시)
        when(table.getColumns()).thenReturn(java.util.Collections.emptyMap());
        when(table.getConstraints()).thenReturn(java.util.Collections.emptyMap());
        when(table.getIndexes()).thenReturn(java.util.Collections.emptyMap());

        TestMigrationVisitor v = new TestMigrationVisitor(dialect, null);

        v.visitAddedTable(table);
        String sql = v.getGeneratedSql();

        // MinimalDialect + CreateTableBuilder(빈 바디) 기대: CREATE … () + 개행
        assertEquals("CREATE TABLE \"users\" ()\n", sql);
    }

    @Test
    @DisplayName("visitDroppedTable은 DROP TABLE SQL을 추가한다(세부 포맷은 구현체에 의존하므로 패턴만 확인)")
    void visitDroppedTable_adds_drop_sql() {
        DdlDialect dialect = new MinimalDialect();
        EntityModel table = mock(EntityModel.class);
        when(table.getTableName()).thenReturn("orders");

        TestMigrationVisitor v = new TestMigrationVisitor(dialect, null);
        v.visitDroppedTable(table);
        String sql = v.getGeneratedSql();

        // DropTableStatementContributor의 정확한 포맷을 알 수 없으니 핵심 토큰만 확인
        assertTrue(sql.contains("DROP TABLE"));
        assertTrue(sql.contains("\"orders\""));
    }

    @Test
    @DisplayName("getGeneratedSql은 alterBuilder가 비어있지 않으면 ALTER SQL을 함께 반환한다")
    void getGeneratedSql_appends_alter_sql_when_present() {
        DdlDialect dialect = new MinimalDialect();

        // diff.newEntity.tableName -> alterBuilder 생성에 필요
        DiffResult.ModifiedEntity diff = mock(DiffResult.ModifiedEntity.class);
        EntityModel newEntity = mock(EntityModel.class);
        when(newEntity.getTableName()).thenReturn("t");
        when(diff.getNewEntity()).thenReturn(newEntity);

        TestMigrationVisitor v = new TestMigrationVisitor(dialect, diff);

        // alterBuilder에 테스트용 유닛 추가
        v.alterBuilder.add(new DdlContributor() {
            @Override public void contribute(StringBuilder sb, DdlDialect d) {
                sb.append("ALTER TABLE ").append(d.quoteIdentifier("t")).append(" ADD COLUMN ").append(d.quoteIdentifier("c")).append(" DUMMY");
            }
            @Override public int priority() { return 10; }
        });

        // 아직 visit* 호출 전이므로 SQL은 alter 한 줄만
        String sql = v.getGeneratedSql();
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"c\" DUMMY", sql);

        // 한 번 더 getGeneratedSql()을 호출해도 idempotent 해야 함(내부적으로 alterBuilder는 동일한 결과)
        String sql2 = v.getGeneratedSql();
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"c\" DUMMY", sql2);
    }

    @Test
    @DisplayName("visitAddedTable 이후 getGeneratedSql 호출 시, CREATE와 ALTER가 순서대로 합쳐진다")
    void create_then_alter_both_joined_in_order() {
        DdlDialect dialect = new MinimalDialect();

        // diff 준비
        DiffResult.ModifiedEntity diff = mock(DiffResult.ModifiedEntity.class);
        EntityModel newEntity = mock(EntityModel.class);
        when(newEntity.getTableName()).thenReturn("users");
        when(diff.getNewEntity()).thenReturn(newEntity);

        // 엔티티(visitAddedTable용)
        EntityModel table = mock(EntityModel.class);
        when(table.getTableName()).thenReturn("users");
        when(table.getColumns()).thenReturn(java.util.Collections.emptyMap());
        when(table.getConstraints()).thenReturn(java.util.Collections.emptyMap());
        when(table.getIndexes()).thenReturn(java.util.Collections.emptyMap());

        TestMigrationVisitor v = new TestMigrationVisitor(dialect, diff);

        // 1) CREATE
        v.visitAddedTable(table);

        // 2) ALTER 추가
        v.alterBuilder.add(new DdlContributor() {
            @Override public void contribute(StringBuilder sb, DdlDialect d) {
                sb.append("ALTER TABLE ").append(d.quoteIdentifier("users"))
                        .append(" ADD COLUMN ").append(d.quoteIdentifier("age")).append(" DUMMY");
            }
            @Override public int priority() { return 5; }
        });

        String sql = v.getGeneratedSql();

        // 순서: CREATE (개행) + ALTER
        String expected = "CREATE TABLE \"users\" ()\n\n" + // CreateTableBuilder는 끝에 \n, AbstractMigrationVisitor는 덧붙일 때 줄바꿈 포함
                "ALTER TABLE \"users\" ADD COLUMN \"age\" DUMMY";
        assertEquals(expected, sql);
    }
}
