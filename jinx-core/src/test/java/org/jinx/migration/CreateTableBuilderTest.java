package org.jinx.migration;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.PostCreateContributor;
import org.jinx.migration.contributor.TableBodyContributor;
import org.jinx.migration.contributor.create.ColumnContributor;
import org.jinx.migration.contributor.create.ConstraintContributor;
import org.jinx.migration.contributor.create.IndexContributor;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateTableBuilderTest {

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
        @Override public SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff) { return null; }
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

        // Constraints & Indexes
        @Override public String getConstraintDefinitionSql(ConstraintModel constraint) { return "/* constraint stub */"; }
        @Override public String getAddConstraintSql(String table, ConstraintModel constraint) { return "/* add constraint stub */"; }
        @Override public String getDropConstraintSql(String table, ConstraintModel constraint) { return "/* drop constraint stub */"; }
        @Override public String getModifyConstraintSql(String table, ConstraintModel newConstraint, ConstraintModel oldConstraint) { return "/* modify constraint stub */"; }
        @Override public String indexStatement(IndexModel idx, String table) { return "/* index stub */"; }
        @Override public String getDropIndexSql(String table, IndexModel index) { return "/* drop index stub */"; }
        @Override public String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex) { return "/* modify index stub */"; }

        // Relationships
        @Override public String getAddRelationshipSql(String table, org.jinx.model.RelationshipModel rel) { return "/* add rel stub */"; }
        @Override public String getDropRelationshipSql(String table, org.jinx.model.RelationshipModel rel) { return "/* drop rel stub */"; }
        @Override public String getModifyRelationshipSql(String table, org.jinx.model.RelationshipModel newRel, org.jinx.model.RelationshipModel oldRel) { return "/* modify rel stub */"; }
    }

    static abstract class StubBody implements DdlContributor, TableBodyContributor {
        private final int p; private final String piece;
        StubBody(int p, String piece) { this.p = p; this.piece = piece; }
        @Override public int priority() { return p; }
        @Override public void contribute(StringBuilder sb, DdlDialect dialect) {
            sb.append(piece);
        }
    }
    static abstract class StubPost implements DdlContributor, PostCreateContributor {
        private final int p; private final String piece;
        StubPost(int p, String piece) { this.p = p; this.piece = piece; }
        @Override public int priority() { return p; }
        @Override public void contribute(StringBuilder sb, DdlDialect dialect) {
            sb.append(piece);
        }
    }

    @Test
    @DisplayName("body/post 분리 + 우선순위 정렬 + 마지막 콤마 제거 + post는 CREATE 이후")
    void build_orders_and_trims_and_posts() {
        DdlDialect dialect = new MinimalDialect();
        CreateTableBuilder b = new CreateTableBuilder("users", dialect);

        // priority: 낮을수록 먼저
        b.add(new StubBody(20, "\"age\" DUMMY,\n"){});   // 나중
        b.add(new StubBody(10, "\"id\" DUMMY,\n"){});    // 먼저
        b.add(new StubPost( 1, "POST1;\n"){});           // 먼저
        b.add(new StubPost( 5, "POST2;\n"){});           // 나중

        String sql = b.build();
        String expected =
                "CREATE TABLE \"users\" (" +
                        "\"id\" DUMMY,\n" +
                        "\"age\" DUMMY" +
                        ")" + "\n" +
                        "POST1;\n" +
                        "POST2;\n";

        assertEquals(expected, sql);
    }

    @Test
    @DisplayName("지원되지 않는 컨트리뷰터 타입이면 예외")
    void add_unsupported_contributor_throws() {
        DdlDialect dialect = new MinimalDialect();
        CreateTableBuilder b = new CreateTableBuilder("x", dialect);

        // TableBodyContributor/PostCreateContributor 둘 다 구현하지 않음
        DdlContributor bad = new DdlContributor() {
            @Override public void contribute(StringBuilder sb, DdlDialect dialect1) { sb.append("BAD"); }
            @Override public int priority() { return 0; }
        };

        assertThrows(IllegalArgumentException.class, () -> b.add(bad));
    }

    @Test
    @DisplayName("defaultsFrom()는 Column/Constraint를 body에, Index를 post에 추가한다(타입 분류 확인)")
    void defaultsFrom_classification() {
        DdlDialect dialect = new MinimalDialect();

        // add(T) 호출을 가로채서 어떤 타입이 추가되는지 기록하는 서브클래스
        class RecordingBuilder extends CreateTableBuilder {
            List<Class<?>> addedTypes = new ArrayList<>();
            List<Boolean> isPost = new ArrayList<>();
            RecordingBuilder(String table, DdlDialect d) { super(table, d); }
            @Override
            public <T extends DdlContributor> CreateTableBuilder add(T c) {
                addedTypes.add(c.getClass());
                isPost.add(c instanceof PostCreateContributor);
                return super.add(c);
            }
        }

        RecordingBuilder b = new RecordingBuilder("t", dialect);

        // ---- 엔티티 목킹: columns / constraints / indexes ----
        ColumnModel id = mock(ColumnModel.class);
        when(id.getColumnName()).thenReturn("id");
        when(id.isPrimaryKey()).thenReturn(true);

        ColumnModel name = mock(ColumnModel.class);
        when(name.getColumnName()).thenReturn("name");
        when(name.isPrimaryKey()).thenReturn(false);

        Map<ColumnKey, ColumnModel> cols = new LinkedHashMap<>();
        ColumnKey key1 = ColumnKey.of("id", "id");
        ColumnKey key2 = ColumnKey.of("name", "name");
        cols.put(key1, id);
        cols.put(key2, name);

        EntityModel entity = mock(EntityModel.class);
        when(entity.getTableName()).thenReturn("t");
        when(entity.getColumns()).thenReturn((Map) cols);
        when(entity.getConstraints()).thenReturn(Collections.emptyMap());
        when(entity.getIndexes()).thenReturn(Collections.emptyMap());

        b.defaultsFrom(entity);

        // 추가된 타입 순서/분류 확인
        assertEquals(3, b.addedTypes.size(), "Column/Constraint/Index 세 가지가 추가되어야 합니다");
        assertEquals(ColumnContributor.class, b.addedTypes.get(0));
        assertEquals(ConstraintContributor.class, b.addedTypes.get(1));
        assertEquals(IndexContributor.class, b.addedTypes.get(2));

        // post 여부: IndexContributor(보통 CREATE 이후)만 post여야 함
        assertFalse(b.isPost.get(0), "ColumnContributor는 body여야 합니다");
        assertFalse(b.isPost.get(1), "ConstraintContributor는 body여야 합니다");
        assertTrue(b.isPost.get(2), "IndexContributor는 post여야 합니다");
    }

    @Test
    @DisplayName("빈 본문인 경우에도 유효한 CREATE TABLE 스켈레톤을 만든다")
    void empty_body_produces_valid_skeleton() {
        DdlDialect dialect = new MinimalDialect();
        CreateTableBuilder b = new CreateTableBuilder("empty", dialect);

        String sql = b.build();
        assertEquals("CREATE TABLE \"empty\" ()\n", sql);
    }

    @Test
    @DisplayName("본문이 콤마로 끝나지 않으면 trimTrailingComma는 안전(no-op)")
    void trim_is_safe_when_no_trailing_comma() {
        DdlDialect dialect = new MinimalDialect();
        CreateTableBuilder b = new CreateTableBuilder("safe", dialect);

        // 마지막에 콤마 없이 끝나는 본문
        b.add(new StubBody(10, "\"id\" DUMMY\n"){}); // \n만 있고 , 없음

        String sql = b.build();
        assertEquals("CREATE TABLE \"safe\" (\"id\" DUMMY\n)\n", sql);
    }
}
