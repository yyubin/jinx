package org.jinx.migration;

import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.BaseDialect;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbstractDialectTest {

    private TestDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new TestDialect();
    }

    @Test
    @DisplayName("생성 시 JavaTypeMapper/ValueTransformer가 초기화된다")
    void initializesDependencies() {
        assertNotNull(dialect.getJavaTypeMapper());
        assertNotNull(dialect.getValueTransformer());
    }

    @Test
    @DisplayName("getValueTransformer는 초기화된 트랜스포머를 반환한다")
    void returnsValueTransformer() {
        ValueTransformer vt = dialect.getValueTransformer();
        JavaTypeMapper.JavaType strType = dialect.getJavaTypeMapper().map("java.lang.String");
        assertEquals("'hello'", vt.quote("hello", strType));
        JavaTypeMapper.JavaType intType = dialect.getJavaTypeMapper().map("java.lang.Integer");
        assertEquals("42", vt.quote("42", intType));
    }

    @Test
    @DisplayName("columnIsIdentity는 동일 이름의 IDENTITY 컬럼이 존재하면 true")
    void columnIsIdentity_trueWhenIdentityPresent() {
        ColumnModel id = mock(ColumnModel.class);
        when(id.getColumnName()).thenReturn("id");
        when(id.getGenerationStrategy()).thenReturn(GenerationStrategy.IDENTITY);

        ColumnModel name = mock(ColumnModel.class);
        when(name.getColumnName()).thenReturn("name");
        when(name.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);

        assertTrue(dialect.invokeColumnIsIdentity("id", List.of(id, name)));
        assertFalse(dialect.invokeColumnIsIdentity("name", List.of(id, name)));
        assertFalse(dialect.invokeColumnIsIdentity("missing", List.of(id, name)));
    }

    @Test
    @DisplayName("quoteIdentifier 구현이 호출되어 식별자를 감싼다")
    void quoteIdentifier_wraps() {
        assertEquals("\"users\"", dialect.quoteIdentifier("users"));
        assertEquals("\"Order\"", dialect.quoteIdentifier("Order"));
    }

    @Test
    @DisplayName("getDropPrimaryKeySql 구현이 호출된다")
    void getDropPrimaryKeySql_returnsString() {
        String sql = dialect.getDropPrimaryKeySql("users", List.of());
        assertEquals("ALTER TABLE \"users\" DROP PRIMARY KEY", sql);
    }

    // === 추가 검증: DdlDialect 최소 동작 ===

    @Test
    @DisplayName("open/closeCreateTable은 간단한 CREATE TABLE 구문을 반환한다")
    void createTable_open_close() {
        assertEquals("CREATE TABLE \"t\" (", dialect.openCreateTable("t"));
        assertEquals(")", dialect.closeCreateTable());
    }

    @Test
    @DisplayName("getCreateTableSql은 엔티티의 테이블명을 사용한다")
    void getCreateTableSql_usesEntityName() {
        EntityModel entity = mock(EntityModel.class);
        when(entity.getTableName()).thenReturn("orders");
        assertEquals("CREATE TABLE \"orders\" ()", dialect.getCreateTableSql(entity));
    }

    @Test
    @DisplayName("테이블 드랍/리네임/알터 구문 확인")
    void table_drop_rename_alter() {
        assertEquals("DROP TABLE \"a\"", dialect.getDropTableSql("a"));
        assertEquals("RENAME TABLE \"a\" TO \"b\"", dialect.getRenameTableSql("a", "b"));
        DiffResult.ModifiedEntity mod = mock(DiffResult.ModifiedEntity.class);
        assertTrue(dialect.getAlterTableSql(mod).startsWith("/* ALTER TABLE stub */"));
    }

    @Test
    @DisplayName("컬럼 정의/추가/드랍/수정/리네임 SQL 확인")
    void column_sqls() {
        ColumnModel newCol = mock(ColumnModel.class);
        when(newCol.getColumnName()).thenReturn("age");
        ColumnModel oldCol = mock(ColumnModel.class);
        when(oldCol.getColumnName()).thenReturn("years");

        assertEquals("\"age\" DUMMY_TYPE", dialect.getColumnDefinitionSql(newCol));
        assertEquals("ALTER TABLE \"person\" ADD COLUMN \"age\" DUMMY_TYPE",
                dialect.getAddColumnSql("person", newCol));
        assertEquals("ALTER TABLE \"person\" DROP COLUMN \"age\"",
                dialect.getDropColumnSql("person", newCol));
        assertEquals("ALTER TABLE \"person\" MODIFY COLUMN \"age\" /* from \"years\" */",
                dialect.getModifyColumnSql("person", newCol, oldCol));
        assertEquals("ALTER TABLE \"person\" RENAME COLUMN \"years\" TO \"age\"",
                dialect.getRenameColumnSql("person", newCol, oldCol));
    }

    @Test
    @DisplayName("PK 정의/추가 SQL 확인")
    void primary_key_sqls() {
        List<String> pk = List.of("id", "createdAt");
        assertEquals("PRIMARY KEY (\"id\", \"createdAt\")", dialect.getPrimaryKeyDefinitionSql(pk));
        assertEquals("ALTER TABLE \"t\" ADD PRIMARY KEY (\"id\", \"createdAt\")",
                dialect.getAddPrimaryKeySql("t", pk));
    }

    @Test
    @DisplayName("제약조건/인덱스/관계 SQL 확인")
    void constraints_indexes_relationships() {
        ConstraintModel c = mock(ConstraintModel.class);
        when(c.getName()).thenReturn("uk_user_email");
        when(c.getType()).thenReturn(ConstraintType.UNIQUE);
        when(c.getColumns()).thenReturn(List.of("email"));

        assertEquals("CONSTRAINT \"uk_user_email\" UNIQUE (\"email\")",
                dialect.getConstraintDefinitionSql(c));
        assertEquals("ALTER TABLE \"user\" ADD CONSTRAINT \"uk_user_email\" UNIQUE (\"email\")",
                dialect.getAddConstraintSql("user", c));
        assertEquals("ALTER TABLE \"user\" DROP CONSTRAINT \"uk_user_email\"",
                dialect.getDropConstraintSql("user", c));
        ConstraintModel c2 = mock(ConstraintModel.class);
        when(c2.getName()).thenReturn("uk_user_email2");
        when(c2.getType()).thenReturn(ConstraintType.UNIQUE);
        when(c2.getColumns()).thenReturn(List.of("email"));
        assertTrue(dialect.getModifyConstraintSql("user", c2, c).startsWith("/* MODIFY CONSTRAINT stub */"));

        IndexModel idx = mock(IndexModel.class);
        when(idx.getIndexName()).thenReturn("idx_user_email");
        when(idx.getColumnNames()).thenReturn(List.of("email"));
        assertEquals("CREATE INDEX \"idx_user_email\" ON \"user\" (\"email\")",
                dialect.indexStatement(idx, "user"));
        assertEquals("DROP INDEX \"idx_user_email\" ON \"user\"",
                dialect.getDropIndexSql("user", idx));
        IndexModel idx2 = mock(IndexModel.class);
        when(idx2.getIndexName()).thenReturn("idx_user_email2");
        when(idx2.getColumnNames()).thenReturn(List.of("email"));
        assertTrue(dialect.getModifyIndexSql("user", idx2, idx).startsWith("/* MODIFY INDEX stub */"));

        RelationshipModel relNew = mock(RelationshipModel.class);
        RelationshipModel relOld = mock(RelationshipModel.class);
        assertEquals("/* ADD RELATIONSHIP stub */ ALTER TABLE \"t\" ADD /* fk */",
                dialect.getAddRelationshipSql("t", relNew));
        assertEquals("/* DROP RELATIONSHIP stub */ ALTER TABLE \"t\" DROP /* fk */",
                dialect.getDropRelationshipSql("t", relOld));
        assertTrue(dialect.getModifyRelationshipSql("t", relNew, relOld).startsWith("/* MODIFY RELATIONSHIP stub */"));
    }

    static class TestDialect extends AbstractDialect implements BaseDialect {

        @Override
        protected JavaTypeMapper initializeJavaTypeMapper() {
            return className -> {
                // 따옴표가 필요한 타입들
                if ("java.lang.String".equals(className)
                        || "java.time.LocalDate".equals(className)
                        || "java.time.LocalDateTime".equals(className)
                        || "java.time.OffsetDateTime".equals(className)
                        || "java.time.Instant".equals(className)) {
                    return new JavaTypeMapper.JavaType() {
                        @Override public String getSqlType(int length, int precision, int scale) { return "DUMMY_TYPE"; }
                        @Override public boolean needsQuotes() { return true; }
                        @Override public String getDefaultValue() { return "NULL"; }
                    };
                }
                // 따옴표가 필요 없는 숫자/불린
                if ("java.lang.Integer".equals(className) || "int".equals(className)
                        || "java.lang.Long".equals(className) || "long".equals(className)
                        || "java.lang.Double".equals(className) || "double".equals(className)
                        || "java.lang.Float".equals(className) || "float".equals(className)
                        || "java.lang.Short".equals(className) || "short".equals(className)
                        || "java.math.BigDecimal".equals(className)
                        || "java.lang.Boolean".equals(className) || "boolean".equals(className)) {
                    return new JavaTypeMapper.JavaType() {
                        @Override public String getSqlType(int length, int precision, int scale) { return "DUMMY_TYPE"; }
                        @Override public boolean needsQuotes() { return false; }
                        @Override public String getDefaultValue() { return "0"; }
                    };
                }
                // 기본값: 따옴표 필요
                return new JavaTypeMapper.JavaType() {
                    @Override public String getSqlType(int length, int precision, int scale) { return "DUMMY_TYPE"; }
                    @Override public boolean needsQuotes() { return true; }
                    @Override public String getDefaultValue() { return "NULL"; }
                };
            };
        }

        @Override
        protected ValueTransformer initializeValueTransformer() {
            return (value, type) -> type.needsQuotes() ? "'" + value + "'" : value;
        }

        @Override
        public String quoteIdentifier(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff) {
            return null;
        }

        @Override
        public JavaTypeMapper getJavaTypeMapper() {
            return this.javaTypeMapper;
        }

        // === DdlDialect: 최소 구현 ===
        @Override
        public String openCreateTable(String tableName) {
            return "CREATE TABLE " + quoteIdentifier(tableName) + " (";
        }

        @Override
        public String closeCreateTable() {
            return ")";
        }

        @Override
        public String getCreateTableSql(EntityModel entity) {
            return "CREATE TABLE " + quoteIdentifier(entity.getTableName()) + " ()";
        }

        @Override
        public String getDropTableSql(String tableName) {
            return "DROP TABLE " + quoteIdentifier(tableName);
        }

        @Override
        public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) {
            return "/* ALTER TABLE stub */";
        }

        @Override
        public String getRenameTableSql(String oldTableName, String newTableName) {
            return "RENAME TABLE " + quoteIdentifier(oldTableName) + " TO " + quoteIdentifier(newTableName);
        }

        @Override
        public String getColumnDefinitionSql(ColumnModel column) {
            return quoteIdentifier(column.getColumnName()) + " DUMMY_TYPE";
        }

        @Override
        public String getAddColumnSql(String table, ColumnModel column) {
            return "ALTER TABLE " + quoteIdentifier(table) + " ADD COLUMN " + getColumnDefinitionSql(column);
        }

        @Override
        public String getDropColumnSql(String table, ColumnModel column) {
            return "ALTER TABLE " + quoteIdentifier(table) + " DROP COLUMN " + quoteIdentifier(column.getColumnName());
        }

        @Override
        public String getModifyColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn) {
            return "ALTER TABLE " + quoteIdentifier(table) + " MODIFY COLUMN " +
                    quoteIdentifier(newColumn.getColumnName()) + " /* from " + quoteIdentifier(oldColumn.getColumnName()) + " */";
        }

        @Override
        public String getRenameColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn) {
            return "ALTER TABLE " + quoteIdentifier(table) + " RENAME COLUMN " +
                    quoteIdentifier(oldColumn.getColumnName()) + " TO " + quoteIdentifier(newColumn.getColumnName());
        }

        @Override
        public String getPrimaryKeyDefinitionSql(List<String> pkColumns) {
            String cols = pkColumns.stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("");
            return "PRIMARY KEY (" + cols + ")";
        }

        @Override
        public String getAddPrimaryKeySql(String table, List<String> pkColumns) {
            String cols = pkColumns.stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("");
            return "ALTER TABLE " + quoteIdentifier(table) + " ADD PRIMARY KEY (" + cols + ")";
        }

        @Override
        public String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns) {
            return "ALTER TABLE " + quoteIdentifier(table) + " DROP PRIMARY KEY";
        }

        @Override
        public String getConstraintDefinitionSql(ConstraintModel constraint) {
            String cols = constraint.getColumns().stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("");
            return "CONSTRAINT " + quoteIdentifier(constraint.getName()) + " " + constraint.getType().name() + " (" + cols + ")";
        }

        @Override
        public String getAddConstraintSql(String table, ConstraintModel constraint) {
            return "ALTER TABLE " + quoteIdentifier(table) + " ADD " + getConstraintDefinitionSql(constraint);
        }

        @Override
        public String getDropConstraintSql(String table, ConstraintModel constraint) {
            return "ALTER TABLE " + quoteIdentifier(table) + " DROP CONSTRAINT " + quoteIdentifier(constraint.getName());
        }

        @Override
        public String getModifyConstraintSql(String table, ConstraintModel newConstraint, ConstraintModel oldConstraint) {
            return "/* MODIFY CONSTRAINT stub */";
        }

        @Override
        public String indexStatement(IndexModel idx, String table) {
            String cols = idx.getColumnNames().stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("");
            return "CREATE INDEX " + quoteIdentifier(idx.getIndexName()) + " ON " + quoteIdentifier(table) + " (" + cols + ")";
        }

        @Override
        public String getDropIndexSql(String table, IndexModel index) {
            return "DROP INDEX " + quoteIdentifier(index.getIndexName()) + " ON " + quoteIdentifier(table);
        }

        @Override
        public String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex) {
            return "/* MODIFY INDEX stub */";
        }

        @Override
        public String getAddRelationshipSql(String table, RelationshipModel rel) {
            return "/* ADD RELATIONSHIP stub */ ALTER TABLE " + quoteIdentifier(table) + " ADD /* fk */";
        }

        @Override
        public String getDropRelationshipSql(String table, RelationshipModel rel) {
            return "/* DROP RELATIONSHIP stub */ ALTER TABLE " + quoteIdentifier(table) + " DROP /* fk */";
        }

        @Override
        public String getModifyRelationshipSql(String table, RelationshipModel newRel, RelationshipModel oldRel) {
            return "/* MODIFY RELATIONSHIP stub */";
        }

        boolean invokeColumnIsIdentity(String colName, Collection<ColumnModel> cols) {
            return columnIsIdentity(colName, cols);
        }
    }
}
