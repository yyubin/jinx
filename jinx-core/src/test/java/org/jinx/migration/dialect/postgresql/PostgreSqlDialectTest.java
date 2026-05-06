package org.jinx.migration.dialect.postgresql;

import jakarta.persistence.TemporalType;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSqlDialectTest {

    // ── 테스트용 간소화 Dialect (실제 타입 매퍼 주입) ─────────────────────────

    private PostgreSqlDialect newDialect() {
        JavaTypeMapper mapper = className -> new JavaTypeMapper.JavaType() {
            @Override public String getSqlType(int length, int precision, int scale) {
                return switch (className) {
                    case "java.lang.String"  -> "VARCHAR(" + (length > 0 ? length : 255) + ")";
                    case "java.lang.Integer", "int"  -> "INTEGER";
                    case "java.lang.Long",   "long"  -> "BIGINT";
                    case "java.lang.Boolean","boolean" -> "BOOLEAN";
                    case "java.math.BigDecimal" -> "NUMERIC(" + (precision > 0 ? precision : 10) + "," + (scale > 0 ? scale : 2) + ")";
                    case "java.util.UUID"    -> "uuid";
                    default -> "TEXT";
                };
            }
            @Override public boolean needsQuotes() {
                return "java.lang.String".equals(className) || "java.time.LocalDate".equals(className)
                        || "java.time.LocalDateTime".equals(className);
            }
            @Override public String getDefaultValue() {
                return "boolean".equals(className) || "java.lang.Boolean".equals(className) ? "false" : null;
            }
        };
        ValueTransformer vt = (value, type) -> type.needsQuotes() ? "'" + value + "'" : value;
        return new PostgreSqlDialect(mapper, vt);
    }

    // ── 공용 컬럼 mock 헬퍼 ────────────────────────────────────────────────

    private ColumnModel col(String name, String javaType) {
        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn(name);
        when(c.getJavaType()).thenReturn(javaType);
        when(c.getLength()).thenReturn(255);
        when(c.getPrecision()).thenReturn(0);
        when(c.getScale()).thenReturn(0);
        when(c.isNullable()).thenReturn(true);
        when(c.getDefaultValue()).thenReturn(null);
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(c.isManualPrimaryKey()).thenReturn(false);
        when(c.isLob()).thenReturn(false);
        when(c.getConversionClass()).thenReturn(null);
        when(c.getConverterOutputType()).thenReturn(null);
        when(c.getSqlTypeOverride()).thenReturn(null);
        when(c.isVersion()).thenReturn(false);
        when(c.getTemporalType()).thenReturn(null);
        when(c.getEnumValues()).thenReturn(new String[]{});
        when(c.isPrimaryKey()).thenReturn(false);
        return c;
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("식별자 인용")
    class QuoteIdentifier {

        @Test @DisplayName("double-quote 사용 (MySQL backtick과 다름)")
        void usesDoubleQuote() {
            assertEquals("\"users\"", newDialect().quoteIdentifier("users"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CREATE TABLE / closeCreateTable")
    class CreateTable {

        @Test @DisplayName("테이블 오픈 구문")
        void openCreateTable() {
            assertEquals("CREATE TABLE \"orders\" (\n", newDialect().openCreateTable("orders"));
        }

        @Test @DisplayName("테이블 닫기 구문 — ENGINE/CHARSET 없음 (MySQL과 다름)")
        void closeCreateTable_noEngineCharset() {
            String close = newDialect().closeCreateTable();
            assertEquals("\n);", close);
            assertFalse(close.contains("ENGINE"), "PG에는 ENGINE이 없어야 함");
            assertFalse(close.contains("CHARSET"), "PG에는 CHARSET이 없어야 함");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DROP TABLE / RENAME TABLE")
    class DropRenameTable {

        @Test @DisplayName("DROP TABLE IF EXISTS")
        void dropTable() {
            assertEquals("DROP TABLE IF EXISTS \"users\";\n", newDialect().getDropTableSql("users"));
        }

        @Test @DisplayName("RENAME TABLE → ALTER TABLE ... RENAME TO ... (MySQL의 RENAME TABLE과 다름)")
        void renameTable_usesAlterRename() {
            String sql = newDialect().getRenameTableSql("old_table", "new_table");
            assertEquals("ALTER TABLE \"old_table\" RENAME TO \"new_table\";\n", sql);
            assertFalse(sql.startsWith("RENAME TABLE"), "PG는 ALTER TABLE RENAME TO 사용");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Primary Key")
    class PrimaryKey {

        @Test @DisplayName("PK 정의")
        void pkDefinition() {
            assertEquals("PRIMARY KEY (\"id\", \"code\")",
                    newDialect().getPrimaryKeyDefinitionSql(List.of("id", "code")));
        }

        @Test @DisplayName("PK 추가")
        void addPk() {
            assertEquals("ALTER TABLE \"users\" ADD PRIMARY KEY (\"id\");\n",
                    newDialect().getAddPrimaryKeySql("users", List.of("id")));
        }

        @Test @DisplayName("PK 드랍 — DROP CONSTRAINT {table}_pkey (MySQL의 DROP PRIMARY KEY와 다름)")
        void dropPk_usesDropConstraint() {
            String sql = newDialect().getDropPrimaryKeySql("orders", List.of());
            assertEquals("ALTER TABLE \"orders\" DROP CONSTRAINT \"orders_pkey\";\n", sql);
            assertFalse(sql.contains("DROP PRIMARY KEY"), "PG는 DROP CONSTRAINT 사용");
        }

        @Test @DisplayName("PK 드랍 — 컬럼 목록에 identity 컬럼이 있어도 AUTO_INCREMENT 제거 단계 없음")
        void dropPk_noAutoIncrementRemoval() {
            PostgreSqlDialect d = newDialect();
            ColumnModel idCol = col("id", "java.lang.Long");
            when(idCol.isPrimaryKey()).thenReturn(true);
            when(idCol.getGenerationStrategy()).thenReturn(GenerationStrategy.IDENTITY);

            String sql = d.getDropPrimaryKeySql("users", List.of(idCol));
            // 딱 한 줄 — MySQL처럼 MODIFY COLUMN으로 AUTO_INCREMENT 먼저 제거하지 않음
            assertEquals(1, sql.lines().filter(l -> !l.isBlank()).count());
            assertTrue(sql.contains("DROP CONSTRAINT"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("컬럼 정의 — getColumnDefinitionSql")
    class ColumnDefinition {

        @Test @DisplayName("기본 NOT NULL + DEFAULT 문자열 컬럼")
        void basicNotNullWithDefault() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("username", "java.lang.String");
            when(c.getLength()).thenReturn(50);
            when(c.isNullable()).thenReturn(false);
            when(c.getDefaultValue()).thenReturn("guest");

            assertEquals("\"username\" VARCHAR(50) NOT NULL DEFAULT 'guest'", d.getColumnDefinitionSql(c));
        }

        @Test @DisplayName("IDENTITY 컬럼 → GENERATED BY DEFAULT AS IDENTITY (MySQL의 AUTO_INCREMENT와 다름)")
        void identityColumn_generatedByDefault() {
            PostgreSqlDialect d = newDialect();
            ColumnModel id = col("id", "java.lang.Long");
            when(id.getGenerationStrategy()).thenReturn(GenerationStrategy.IDENTITY);
            when(id.isNullable()).thenReturn(false);

            String def = d.getColumnDefinitionSql(id);
            assertTrue(def.contains("GENERATED BY DEFAULT AS IDENTITY"), "PG identity clause 포함");
            assertFalse(def.contains("AUTO_INCREMENT"), "MySQL AUTO_INCREMENT 미포함");
        }

        @Test @DisplayName("AUTO 전략도 GENERATED BY DEFAULT AS IDENTITY")
        void autoStrategy_alsoUsesIdentity() {
            PostgreSqlDialect d = newDialect();
            ColumnModel id = col("id", "java.lang.Long");
            when(id.getGenerationStrategy()).thenReturn(GenerationStrategy.AUTO);
            when(id.isNullable()).thenReturn(false);

            assertTrue(d.getColumnDefinitionSql(id).contains("GENERATED BY DEFAULT AS IDENTITY"));
        }

        @Test @DisplayName("LOB String → TEXT (MySQL과 동일)")
        void lobString_text() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("content", "java.lang.String");
            when(c.isLob()).thenReturn(true);

            assertEquals("\"content\" TEXT", d.getColumnDefinitionSql(c));
        }

        @Test @DisplayName("LOB binary → BYTEA (MySQL의 BLOB과 다름)")
        void lobBinary_bytea() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("data", "byte[]");
            when(c.isLob()).thenReturn(true);

            String def = d.getColumnDefinitionSql(c);
            assertTrue(def.contains("BYTEA"), "PG binary LOB은 BYTEA");
            assertFalse(def.contains("BLOB"), "MySQL BLOB 미포함");
        }

        @Test @DisplayName("UUID 전략 → gen_random_uuid() DEFAULT (MySQL의 UUID()와 다름)")
        void uuidStrategy_genRandomUuid() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("uid", "java.util.UUID");
            when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.UUID);

            String def = d.getColumnDefinitionSql(c);
            assertTrue(def.contains("gen_random_uuid()"), "PG uuid default 함수");
            assertFalse(def.contains("UUID()"), "MySQL UUID() 미포함");
        }

        @Test @DisplayName("Boolean 컬럼 default 'false' (MySQL의 0과 다름)")
        void booleanDefault_false() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("active", "boolean");

            String def = d.getColumnDefinitionSql(c);
            assertTrue(def.contains("BOOLEAN"), "BOOLEAN 타입 포함");
            assertTrue(def.contains("DEFAULT 'false'") || def.contains("DEFAULT false"),
                    "boolean default 값 포함: " + def);
        }

        @Test @DisplayName("TemporalType.TIMESTAMP → TIMESTAMP (MySQL의 DATETIME과 다름)")
        void temporalTimestamp() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("created_at", "java.util.Date");
            when(c.getTemporalType()).thenReturn(TemporalType.TIMESTAMP);

            String def = d.getColumnDefinitionSql(c);
            assertTrue(def.contains("TIMESTAMP"));
            assertFalse(def.contains("DATETIME"), "PG에는 DATETIME 없음");
        }

        @Test @DisplayName("Enum string 매핑 → VARCHAR")
        void enumStringMapping_varchar() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("status", "com.example.Status");
            when(c.getEnumValues()).thenReturn(new String[]{"ACTIVE", "INACTIVE"});
            when(c.isEnumStringMapping()).thenReturn(true);
            when(c.getLength()).thenReturn(20);

            String def = d.getColumnDefinitionSql(c);
            assertTrue(def.contains("VARCHAR(20)"), "PG enum string → VARCHAR");
            assertFalse(def.contains("ENUM("), "PG는 ENUM 타입 사용 안 함");
        }

        @Test @DisplayName("Enum ordinal 매핑 → INTEGER")
        void enumOrdinalMapping_integer() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("priority", "com.example.Priority");
            when(c.getEnumValues()).thenReturn(new String[]{"LOW", "HIGH"});
            when(c.isEnumStringMapping()).thenReturn(false);

            assertTrue(d.getColumnDefinitionSql(c).contains("INTEGER"));
        }

        @Test @DisplayName("sqlTypeOverride에 generated 키워드 포함 시 identity clause 미추가")
        void overrideContainsGenerated_noExtraIdentity() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("id", "java.lang.Long");
            when(c.getSqlTypeOverride()).thenReturn("BIGINT GENERATED ALWAYS AS IDENTITY");
            when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.IDENTITY);

            String def = d.getColumnDefinitionSql(c);
            // GENERATED가 두 번 나오면 안 됨
            assertEquals(1, countOccurrences(def.toUpperCase(), "GENERATED"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("컬럼 ADD / DROP / MODIFY / RENAME")
    class ColumnOperations {

        @Test @DisplayName("ADD COLUMN")
        void addColumn() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("email", "java.lang.String");
            when(c.getLength()).thenReturn(100);
            when(c.isNullable()).thenReturn(false);

            assertTrue(d.getAddColumnSql("users", c)
                    .startsWith("ALTER TABLE \"users\" ADD COLUMN \"email\""));
        }

        @Test @DisplayName("DROP COLUMN (pk 아닌 컬럼)")
        void dropColumn_nonPk() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("email", "java.lang.String");

            assertEquals("ALTER TABLE \"users\" DROP COLUMN \"email\";\n",
                    d.getDropColumnSql("users", c));
        }

        @Test @DisplayName("DROP COLUMN (pk 컬럼) — DROP CONSTRAINT 선행")
        void dropColumn_pk_dropsPkConstraintFirst() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("id", "java.lang.Long");
            when(c.isPrimaryKey()).thenReturn(true);

            String sql = d.getDropColumnSql("orders", c);
            // DROP CONSTRAINT가 DROP COLUMN 앞에 와야 함
            int constraintIdx = sql.indexOf("DROP CONSTRAINT");
            int columnIdx = sql.indexOf("DROP COLUMN");
            assertTrue(constraintIdx >= 0, "DROP CONSTRAINT 포함");
            assertTrue(columnIdx >= 0, "DROP COLUMN 포함");
            assertTrue(constraintIdx < columnIdx, "DROP CONSTRAINT가 DROP COLUMN 앞에 와야 함");
        }

        @Test @DisplayName("MODIFY COLUMN — 타입 변경 → ALTER COLUMN TYPE 생성")
        void modifyColumn_typeChange() {
            PostgreSqlDialect d = newDialect();
            ColumnModel newC = col("name", "java.lang.String");
            when(newC.getLength()).thenReturn(100);
            ColumnModel oldC = col("name", "java.lang.String");
            when(oldC.getLength()).thenReturn(50);

            String sql = d.getModifyColumnSql("users", newC, oldC);
            assertTrue(sql.contains("ALTER COLUMN \"name\" TYPE VARCHAR(100)"),
                    "타입 변경 구문 포함: " + sql);
            assertFalse(sql.contains("MODIFY COLUMN"), "MySQL MODIFY COLUMN 미사용");
        }

        @Test @DisplayName("MODIFY COLUMN — nullable 변경 → SET NOT NULL / DROP NOT NULL")
        void modifyColumn_nullableChange() {
            PostgreSqlDialect d = newDialect();

            // nullable true → false
            ColumnModel newC = col("name", "java.lang.String");
            when(newC.isNullable()).thenReturn(false);
            ColumnModel oldC = col("name", "java.lang.String");
            when(oldC.isNullable()).thenReturn(true);

            String sql = d.getModifyColumnSql("t", newC, oldC);
            assertTrue(sql.contains("SET NOT NULL"), "NOT NULL 설정 구문");

            // nullable false → true
            ColumnModel newC2 = col("name", "java.lang.String");
            when(newC2.isNullable()).thenReturn(true);
            ColumnModel oldC2 = col("name", "java.lang.String");
            when(oldC2.isNullable()).thenReturn(false);

            String sql2 = d.getModifyColumnSql("t", newC2, oldC2);
            assertTrue(sql2.contains("DROP NOT NULL"), "NOT NULL 제거 구문");
        }

        @Test @DisplayName("MODIFY COLUMN — default 추가/제거")
        void modifyColumn_defaultChange() {
            PostgreSqlDialect d = newDialect();

            // default 추가
            ColumnModel newC = col("name", "java.lang.String");
            when(newC.getDefaultValue()).thenReturn("unknown");
            ColumnModel oldC = col("name", "java.lang.String");

            String sql = d.getModifyColumnSql("t", newC, oldC);
            assertTrue(sql.contains("SET DEFAULT"), "DEFAULT 설정 구문: " + sql);

            // default 제거
            ColumnModel newC2 = col("name", "java.lang.String");
            ColumnModel oldC2 = col("name", "java.lang.String");
            when(oldC2.getDefaultValue()).thenReturn("old_default");

            String sql2 = d.getModifyColumnSql("t", newC2, oldC2);
            assertTrue(sql2.contains("DROP DEFAULT"), "DEFAULT 제거 구문: " + sql2);
        }

        @Test @DisplayName("MODIFY COLUMN — 변경 없으면 빈 문자열")
        void modifyColumn_noChange_empty() {
            PostgreSqlDialect d = newDialect();
            ColumnModel c = col("name", "java.lang.String");
            when(c.getLength()).thenReturn(50);

            String sql = d.getModifyColumnSql("t", c, c);
            assertTrue(sql.isBlank(), "변경 없으면 빈 문자열: '" + sql + "'");
        }

        @Test @DisplayName("RENAME COLUMN")
        void renameColumn() {
            PostgreSqlDialect d = newDialect();
            ColumnModel newC = col("new_name", "java.lang.String");
            ColumnModel oldC = col("old_name", "java.lang.String");

            assertEquals("ALTER TABLE \"t\" RENAME COLUMN \"old_name\" TO \"new_name\";\n",
                    d.getRenameColumnSql("t", newC, oldC));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("제약 조건 (Constraint)")
    class Constraints {

        @Test @DisplayName("UNIQUE 정의 및 ADD/DROP — DROP은 DROP CONSTRAINT (MySQL의 DROP INDEX와 다름)")
        void unique_addDrop() {
            PostgreSqlDialect d = newDialect();
            ConstraintModel u = mock(ConstraintModel.class);
            when(u.getType()).thenReturn(ConstraintType.UNIQUE);
            when(u.getName()).thenReturn("uq_email");
            when(u.getColumns()).thenReturn(List.of("email"));
            when(u.getTableName()).thenReturn("users");

            assertEquals("CONSTRAINT \"uq_email\" UNIQUE (\"email\")", d.getConstraintDefinitionSql(u));
            assertEquals("ALTER TABLE \"users\" ADD CONSTRAINT \"uq_email\" UNIQUE (\"email\");\n",
                    d.getAddConstraintSql("users", u));

            String drop = d.getDropConstraintSql("users", u);
            assertEquals("ALTER TABLE \"users\" DROP CONSTRAINT \"uq_email\";\n", drop);
            assertFalse(drop.contains("DROP INDEX"), "PG UNIQUE는 DROP INDEX가 아닌 DROP CONSTRAINT 사용");
        }

        @Test @DisplayName("CHECK 정의 및 DROP — DROP CONSTRAINT (MySQL의 DROP CHECK와 다름)")
        void check_dropConstraint() {
            PostgreSqlDialect d = newDialect();
            ConstraintModel ck = mock(ConstraintModel.class);
            when(ck.getType()).thenReturn(ConstraintType.CHECK);
            when(ck.getName()).thenReturn("chk_age");
            when(ck.getCheckClause()).thenReturn("age > 0");
            when(ck.getColumns()).thenReturn(List.of());
            when(ck.getTableName()).thenReturn("users");

            String def = d.getConstraintDefinitionSql(ck);
            assertFalse(def.contains("WARNING"), "PG는 CHECK 지원하므로 경고 불필요");
            assertTrue(def.contains("CHECK (age > 0)"));

            String drop = d.getDropConstraintSql("users", ck);
            assertEquals("ALTER TABLE \"users\" DROP CONSTRAINT \"chk_age\";\n", drop);
            assertFalse(drop.contains("DROP CHECK"), "MySQL의 DROP CHECK 미사용");
        }

        @Test @DisplayName("PRIMARY_KEY DROP — cons.getName() 우선 사용")
        void pkConstraint_drop_usesConstraintName() {
            PostgreSqlDialect d = newDialect();
            ConstraintModel pk = mock(ConstraintModel.class);
            when(pk.getType()).thenReturn(ConstraintType.PRIMARY_KEY);
            when(pk.getName()).thenReturn("pk_users_custom");
            when(pk.getColumns()).thenReturn(List.of("id"));
            when(pk.getTableName()).thenReturn("users");

            String drop = d.getDropConstraintSql("users", pk);
            assertEquals("ALTER TABLE \"users\" DROP CONSTRAINT \"pk_users_custom\";\n", drop);
        }

        @Test @DisplayName("PRIMARY_KEY DROP — cons.getName() null이면 {table}_pkey fallback")
        void pkConstraint_drop_fallbackToPkey() {
            PostgreSqlDialect d = newDialect();
            ConstraintModel pk = mock(ConstraintModel.class);
            when(pk.getType()).thenReturn(ConstraintType.PRIMARY_KEY);
            when(pk.getName()).thenReturn(null);
            when(pk.getColumns()).thenReturn(List.of("id"));
            when(pk.getTableName()).thenReturn("users");

            String drop = d.getDropConstraintSql("users", pk);
            assertEquals("ALTER TABLE \"users\" DROP CONSTRAINT \"users_pkey\";\n", drop);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("인덱스 (Index)")
    class Indexes {

        @Test @DisplayName("인덱스 생성 — ON 절 포함 (PG도 동일)")
        void indexStatement() {
            PostgreSqlDialect d = newDialect();
            IndexModel idx = mock(IndexModel.class);
            when(idx.getIndexName()).thenReturn("ix_email");
            when(idx.getColumnNames()).thenReturn(List.of("email"));

            assertEquals("CREATE INDEX \"ix_email\" ON \"users\" (\"email\");\n",
                    d.indexStatement(idx, "users"));
        }

        @Test @DisplayName("DROP INDEX — ON table_name 없음 (MySQL과 다름)")
        void dropIndex_noOnClause() {
            PostgreSqlDialect d = newDialect();
            IndexModel idx = mock(IndexModel.class);
            when(idx.getIndexName()).thenReturn("ix_email");
            when(idx.getColumnNames()).thenReturn(List.of("email"));

            String sql = d.getDropIndexSql("users", idx);
            assertEquals("DROP INDEX IF EXISTS \"ix_email\";\n", sql);
            assertFalse(sql.contains("ON \"users\""), "PG DROP INDEX에는 ON절 없음");
        }

        @Test @DisplayName("인덱스 MODIFY — DROP + CREATE")
        void modifyIndex() {
            PostgreSqlDialect d = newDialect();
            IndexModel oldIdx = mock(IndexModel.class);
            when(oldIdx.getIndexName()).thenReturn("ix_old");
            when(oldIdx.getColumnNames()).thenReturn(List.of("col"));
            IndexModel newIdx = mock(IndexModel.class);
            when(newIdx.getIndexName()).thenReturn("ix_new");
            when(newIdx.getColumnNames()).thenReturn(List.of("col"));

            String sql = d.getModifyIndexSql("t", newIdx, oldIdx);
            assertTrue(sql.contains("DROP INDEX IF EXISTS \"ix_old\""));
            assertTrue(sql.contains("CREATE INDEX \"ix_new\""));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("외래키 (Relationship)")
    class Relationships {

        @Test @DisplayName("NO_CONSTRAINT이면 빈 문자열")
        void noConstraint_empty() {
            PostgreSqlDialect d = newDialect();
            RelationshipModel rel = mock(RelationshipModel.class);
            when(rel.isNoConstraint()).thenReturn(true);

            assertEquals("", d.getAddRelationshipSql("t", rel));
            assertEquals("", d.getDropRelationshipSql("t", rel));
        }

        @Test @DisplayName("FK 추가 — ON DELETE CASCADE 포함")
        void addRelationship_onDeleteCascade() {
            PostgreSqlDialect d = newDialect();
            RelationshipModel rel = mock(RelationshipModel.class);
            when(rel.isNoConstraint()).thenReturn(false);
            when(rel.getTableName()).thenReturn("orders");
            when(rel.getConstraintName()).thenReturn("fk_orders_user");
            when(rel.getColumns()).thenReturn(List.of("user_id"));
            when(rel.getReferencedTable()).thenReturn("users");
            when(rel.getReferencedColumns()).thenReturn(List.of("id"));
            when(rel.getOnDelete()).thenReturn(OnDeleteAction.CASCADE);
            when(rel.getOnUpdate()).thenReturn(OnUpdateAction.NO_ACTION);

            String sql = d.getAddRelationshipSql("ignored", rel);
            assertEquals(
                    "ALTER TABLE \"orders\" ADD CONSTRAINT \"fk_orders_user\" " +
                    "FOREIGN KEY (\"user_id\") REFERENCES \"users\" (\"id\") ON DELETE CASCADE;\n",
                    sql);
        }

        @Test @DisplayName("FK 삭제 — DROP CONSTRAINT (MySQL의 DROP FOREIGN KEY와 다름)")
        void dropRelationship_dropConstraint() {
            PostgreSqlDialect d = newDialect();
            RelationshipModel rel = mock(RelationshipModel.class);
            when(rel.isNoConstraint()).thenReturn(false);
            when(rel.getTableName()).thenReturn("orders");
            when(rel.getConstraintName()).thenReturn("fk_orders_user");
            when(rel.getColumns()).thenReturn(List.of("user_id"));

            String sql = d.getDropRelationshipSql("ignored", rel);
            assertEquals("ALTER TABLE \"orders\" DROP CONSTRAINT \"fk_orders_user\";\n", sql);
            assertFalse(sql.contains("DROP FOREIGN KEY"), "PG는 DROP CONSTRAINT 사용");
        }

        @Test @DisplayName("FK MODIFY — DROP + ADD")
        void modifyRelationship() {
            PostgreSqlDialect d = newDialect();
            RelationshipModel rel = mock(RelationshipModel.class);
            when(rel.isNoConstraint()).thenReturn(false);
            when(rel.getTableName()).thenReturn("t");
            when(rel.getConstraintName()).thenReturn("fk_name");
            when(rel.getColumns()).thenReturn(List.of("col"));
            when(rel.getReferencedTable()).thenReturn("ref");
            when(rel.getReferencedColumns()).thenReturn(List.of("id"));
            when(rel.getOnDelete()).thenReturn(OnDeleteAction.NO_ACTION);
            when(rel.getOnUpdate()).thenReturn(OnUpdateAction.NO_ACTION);

            String sql = d.getModifyRelationshipSql("t", rel, rel);
            assertTrue(sql.contains("DROP CONSTRAINT"), "DROP 먼저");
            assertTrue(sql.contains("ADD CONSTRAINT"), "ADD 뒤에");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sequence DDL")
    class SequenceDdl {

        @Test @DisplayName("시퀀스 생성 — non-null 필드만 DDL에 포함, PG 문서 순서 준수")
        void createSequence_nonNullFieldsIncluded() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder()
                    .name("order_seq")
                    .initialValue(1L)
                    .allocationSize(50)
                    .cache(10)
                    .build();

            String sql = d.getCreateSequenceSql(seq);
            assertTrue(sql.startsWith("CREATE SEQUENCE IF NOT EXISTS \"order_seq\""));
            assertTrue(sql.contains("START WITH 1"));
            assertTrue(sql.contains("INCREMENT BY 50"));
            assertTrue(sql.contains("CACHE 10"));
            assertFalse(sql.contains("MINVALUE"), "minValue가 null이면 미포함");
            assertFalse(sql.contains("MAXVALUE"), "maxValue가 null이면 미포함");

            // PG 문서 순서: INCREMENT → MINVALUE → MAXVALUE → START WITH → CACHE
            int incIdx   = sql.indexOf("INCREMENT BY");
            int startIdx = sql.indexOf("START WITH");
            int cacheIdx = sql.indexOf("CACHE");
            assertTrue(incIdx < startIdx, "INCREMENT BY가 START WITH 앞에 위치해야 함");
            assertTrue(startIdx < cacheIdx, "START WITH가 CACHE 앞에 위치해야 함");
        }

        @Test @DisplayName("시퀀스 생성 — 모든 필드 null이면 이름만 출력 (DB 기본값 사용)")
        void createSequence_allNullFields_onlyName() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder().name("bare_seq").build();

            String sql = d.getCreateSequenceSql(seq);
            assertTrue(sql.startsWith("CREATE SEQUENCE IF NOT EXISTS \"bare_seq\""));
            assertFalse(sql.contains("START WITH"),   "null initialValue → START WITH 미포함");
            assertFalse(sql.contains("INCREMENT BY"), "null allocationSize → INCREMENT BY 미포함");
            assertFalse(sql.contains("CACHE"),        "null cache → CACHE 미포함");
            assertFalse(sql.contains("MINVALUE"),     "null minValue → MINVALUE 미포함");
            assertFalse(sql.contains("MAXVALUE"),     "null maxValue → MAXVALUE 미포함");
        }

        @Test @DisplayName("시퀀스 생성 — START WITH 0 지원 (> 0 조건으로 silent 무시되지 않음)")
        void createSequence_startWithZero() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder()
                    .name("seq")
                    .initialValue(0L)
                    .build();

            assertTrue(d.getCreateSequenceSql(seq).contains("START WITH 0"),
                    "0은 유효한 START WITH 값 — null이 아니므로 포함되어야 함");
        }

        @Test @DisplayName("시퀀스 생성 — 내림차순 시퀀스 (INCREMENT BY -1, 음수 MINVALUE)")
        void createSequence_descendingSequence() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder()
                    .name("desc_seq")
                    .initialValue(100L)
                    .allocationSize(-1)
                    .minValue(-999999L)
                    .maxValue(100L)
                    .build();

            String sql = d.getCreateSequenceSql(seq);
            assertTrue(sql.contains("INCREMENT BY -1"),   "음수 increment 지원");
            assertTrue(sql.contains("MINVALUE -999999"),  "음수 minValue 지원");
            assertTrue(sql.contains("MAXVALUE 100"),      "양수 maxValue 포함");
        }

        @Test @DisplayName("시퀀스 생성 — CACHE 0 지원 (캐시 비활성화)")
        void createSequence_cacheZero() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder()
                    .name("nocache_seq")
                    .cache(0)
                    .build();

            assertTrue(d.getCreateSequenceSql(seq).contains("CACHE 0"),
                    "CACHE 0은 유효 — null이 아니므로 포함되어야 함");
        }

        @Test @DisplayName("시퀀스 생성 — schema 지정 시 schema.name 형식")
        void createSequence_withSchema() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder()
                    .name("order_seq")
                    .schema("public")
                    .initialValue(1L)
                    .allocationSize(1)
                    .build();

            assertTrue(d.getCreateSequenceSql(seq).contains("\"public\".\"order_seq\""));
        }

        @Test @DisplayName("시퀀스 드랍 — IF EXISTS 포함")
        void dropSequence() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder().name("order_seq").build();
            assertEquals("DROP SEQUENCE IF EXISTS \"order_seq\";\n", d.getDropSequenceSql(seq));
        }

        @Test @DisplayName("시퀀스 ALTER — INCREMENT BY 변경")
        void alterSequence_incrementChanged() {
            PostgreSqlDialect d = newDialect();
            SequenceModel oldSeq = SequenceModel.builder().name("seq").allocationSize(50).build();
            SequenceModel newSeq = SequenceModel.builder().name("seq").allocationSize(100).build();

            String sql = d.getAlterSequenceSql(newSeq, oldSeq);
            assertTrue(sql.contains("ALTER SEQUENCE \"seq\""));
            assertTrue(sql.contains("INCREMENT BY 100"));
        }

        @Test @DisplayName("시퀀스 ALTER — 음수 INCREMENT (내림차순 전환)")
        void alterSequence_negativeIncrement() {
            PostgreSqlDialect d = newDialect();
            SequenceModel oldSeq = SequenceModel.builder().name("seq").allocationSize(50).build();
            SequenceModel newSeq = SequenceModel.builder().name("seq").allocationSize(-1).build();

            String sql = d.getAlterSequenceSql(newSeq, oldSeq);
            assertTrue(sql.contains("INCREMENT BY -1"), "음수 increment ALTER 지원");
        }

        @Test @DisplayName("시퀀스 ALTER — MINVALUE 추가/변경/제거")
        void alterSequence_minValueChanges() {
            PostgreSqlDialect d = newDialect();

            // null → 값: MINVALUE 추가
            SequenceModel noMin = SequenceModel.builder().name("seq").build();
            SequenceModel withMin = SequenceModel.builder().name("seq").minValue(1L).build();
            assertTrue(d.getAlterSequenceSql(withMin, noMin).contains("MINVALUE 1"));

            // 값 변경
            SequenceModel newMin = SequenceModel.builder().name("seq").minValue(-100L).build();
            assertTrue(d.getAlterSequenceSql(newMin, withMin).contains("MINVALUE -100"), "음수 MINVALUE 지원");

            // 값 → null: NO MINVALUE
            String dropMin = d.getAlterSequenceSql(noMin, withMin);
            assertTrue(dropMin.contains("NO MINVALUE"), "MINVALUE 제거 → NO MINVALUE");
        }

        @Test @DisplayName("시퀀스 ALTER — MAXVALUE 추가/변경/제거")
        void alterSequence_maxValueChanges() {
            PostgreSqlDialect d = newDialect();

            SequenceModel noMax = SequenceModel.builder().name("seq").build();
            SequenceModel withMax = SequenceModel.builder().name("seq").maxValue(9999L).build();
            assertTrue(d.getAlterSequenceSql(withMax, noMax).contains("MAXVALUE 9999"));

            String dropMax = d.getAlterSequenceSql(noMax, withMax);
            assertTrue(dropMax.contains("NO MAXVALUE"), "MAXVALUE 제거 → NO MAXVALUE");
        }

        @Test @DisplayName("시퀀스 ALTER — RESTART WITH (initialValue 변경)")
        void alterSequence_restartWith() {
            PostgreSqlDialect d = newDialect();
            SequenceModel oldSeq = SequenceModel.builder().name("seq").initialValue(1L).build();
            SequenceModel newSeq = SequenceModel.builder().name("seq").initialValue(1000L).build();

            String sql = d.getAlterSequenceSql(newSeq, oldSeq);
            assertTrue(sql.contains("RESTART WITH 1000"), "initialValue 변경 → RESTART WITH");
        }

        @Test @DisplayName("시퀀스 ALTER — 복수 속성 동시 변경, 단일 구문, PG 문서 순서 준수")
        void alterSequence_multipleChanges() {
            PostgreSqlDialect d = newDialect();
            SequenceModel oldSeq = SequenceModel.builder().name("seq")
                    .allocationSize(50).cache(10).build();
            SequenceModel newSeq = SequenceModel.builder().name("seq")
                    .allocationSize(100).minValue(1L).maxValue(99999L).cache(20).initialValue(500L).build();

            String sql = d.getAlterSequenceSql(newSeq, oldSeq);
            assertTrue(sql.contains("INCREMENT BY 100"));
            assertTrue(sql.contains("MINVALUE 1"));
            assertTrue(sql.contains("MAXVALUE 99999"));
            assertTrue(sql.contains("RESTART WITH 500"));
            assertTrue(sql.contains("CACHE 20"));
            assertEquals(1, sql.lines().filter(l -> l.startsWith("ALTER SEQUENCE")).count(),
                    "모든 변경이 단일 ALTER SEQUENCE 구문에 포함");

            // PG 문서 순서: INCREMENT → MINVALUE → MAXVALUE → RESTART → CACHE
            int incIdx     = sql.indexOf("INCREMENT BY");
            int minIdx     = sql.indexOf("MINVALUE");
            int maxIdx     = sql.indexOf("MAXVALUE");
            int restartIdx = sql.indexOf("RESTART WITH");
            int cacheIdx   = sql.indexOf("CACHE");
            assertTrue(incIdx < minIdx,     "INCREMENT BY → MINVALUE 순서");
            assertTrue(minIdx < maxIdx,     "MINVALUE → MAXVALUE 순서");
            assertTrue(maxIdx < restartIdx, "MAXVALUE → RESTART WITH 순서");
            assertTrue(restartIdx < cacheIdx, "RESTART WITH → CACHE 순서");
        }

        @Test @DisplayName("시퀀스 ALTER — 변경 없으면 빈 문자열")
        void alterSequence_noChange_empty() {
            PostgreSqlDialect d = newDialect();
            SequenceModel seq = SequenceModel.builder().name("seq").allocationSize(50).build();
            assertTrue(d.getAlterSequenceSql(seq, seq).isBlank());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TableGenerator DDL — ON CONFLICT DO NOTHING 사용")
    class TableGeneratorDdl {

        @Test @DisplayName("테이블 제너레이터 생성 — INSERT ON CONFLICT DO NOTHING (MySQL의 INSERT IGNORE와 다름)")
        void createTableGenerator_onConflictDoNothing() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel tg = mock(TableGeneratorModel.class);
            when(tg.getTable()).thenReturn("seq_table");
            when(tg.getPkColumnName()).thenReturn("k");
            when(tg.getValueColumnName()).thenReturn("v");
            when(tg.getPkColumnValue()).thenReturn("ORDER_SEQ");
            when(tg.getInitialValue()).thenReturn(1L);

            String sql = d.getCreateTableGeneratorSql(tg);
            assertTrue(sql.contains("ON CONFLICT DO NOTHING"), "PG는 ON CONFLICT DO NOTHING 사용");
            assertFalse(sql.contains("INSERT IGNORE"), "MySQL INSERT IGNORE 미사용");
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"seq_table\""));
        }

        @Test @DisplayName("테이블 제너레이터 드랍")
        void dropTableGenerator() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel tg = mock(TableGeneratorModel.class);
            when(tg.getTable()).thenReturn("seq_table");

            assertEquals("DROP TABLE IF EXISTS \"seq_table\";\n", d.getDropTableGeneratorSql(tg));
        }

        @Test @DisplayName("ALTER — 변경 없으면 빈 문자열")
        void alterTableGenerator_noChange_empty() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel tg = tg("seq_table", "k", "v", "ORDER_SEQ", 1L);

            assertTrue(d.getAlterTableGeneratorSql(tg, tg).isBlank());
        }

        @Test @DisplayName("ALTER — 테이블 이름 변경 → DROP + CREATE 재생성")
        void alterTableGenerator_tableNameChanged_dropCreate() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("old_seq", "k", "v", "SEQ", 1L);
            TableGeneratorModel newTg = tg("new_seq", "k", "v", "SEQ", 1L);

            String sql = d.getAlterTableGeneratorSql(newTg, oldTg);
            assertTrue(sql.contains("DROP TABLE IF EXISTS \"old_seq\""), "이전 테이블 DROP");
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"new_seq\""), "새 테이블 CREATE");
        }

        @Test @DisplayName("ALTER — pkColumnName 변경 → RENAME COLUMN")
        void alterTableGenerator_pkColumnNameChanged() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("seq_table", "old_pk", "v", "SEQ", 1L);
            TableGeneratorModel newTg = tg("seq_table", "new_pk", "v", "SEQ", 1L);

            String sql = d.getAlterTableGeneratorSql(newTg, oldTg);
            assertTrue(sql.contains("RENAME COLUMN \"old_pk\" TO \"new_pk\""));
            assertFalse(sql.contains("UPDATE"), "컬럼 이름 변경만이므로 UPDATE 불필요");
        }

        @Test @DisplayName("ALTER — valueColumnName 변경 → RENAME COLUMN")
        void alterTableGenerator_valueColumnNameChanged() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("seq_table", "k", "old_val", "SEQ", 1L);
            TableGeneratorModel newTg = tg("seq_table", "k", "new_val", "SEQ", 1L);

            String sql = d.getAlterTableGeneratorSql(newTg, oldTg);
            assertTrue(sql.contains("RENAME COLUMN \"old_val\" TO \"new_val\""));
        }

        @Test @DisplayName("ALTER — pkColumnValue 변경 → UPDATE row key")
        void alterTableGenerator_pkColumnValueChanged() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("seq_table", "k", "v", "OLD_SEQ", 1L);
            TableGeneratorModel newTg = tg("seq_table", "k", "v", "NEW_SEQ", 1L);

            String sql = d.getAlterTableGeneratorSql(newTg, oldTg);
            assertTrue(sql.contains("UPDATE \"seq_table\""));
            assertTrue(sql.contains("SET \"k\" = 'NEW_SEQ'"));
            assertTrue(sql.contains("WHERE \"k\" = 'OLD_SEQ'"));
        }

        @Test @DisplayName("ALTER — initialValue 변경 → UPDATE row value")
        void alterTableGenerator_initialValueChanged() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("seq_table", "k", "v", "SEQ", 1L);
            TableGeneratorModel newTg = tg("seq_table", "k", "v", "SEQ", 100L);

            String sql = d.getAlterTableGeneratorSql(newTg, oldTg);
            assertTrue(sql.contains("UPDATE \"seq_table\""));
            assertTrue(sql.contains("SET \"v\" = 100"));
            assertTrue(sql.contains("WHERE \"k\" = 'SEQ'"));
        }

        @Test @DisplayName("ALTER — allocationSize만 변경 → SQL 없음 (애플리케이션 레벨)")
        void alterTableGenerator_allocationSizeOnly_noSql() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("seq_table", "k", "v", "SEQ", 1L);
            TableGeneratorModel newTg = TableGeneratorModel.builder()
                    .table("seq_table").pkColumnName("k").valueColumnName("v")
                    .pkColumnValue("SEQ").initialValue(1L).allocationSize(100).build();

            assertTrue(d.getAlterTableGeneratorSql(newTg, oldTg).isBlank(),
                    "allocationSize 변경은 SQL 불필요");
        }

        @Test @DisplayName("ALTER — 복수 변경: 컬럼 이름 + pkValue + initialValue 동시 처리")
        void alterTableGenerator_multipleChanges_correctOrder() {
            PostgreSqlDialect d = newDialect();
            TableGeneratorModel oldTg = tg("seq_table", "old_pk", "old_val", "OLD", 1L);
            TableGeneratorModel newTg = tg("seq_table", "new_pk", "new_val", "NEW", 50L);

            String sql = d.getAlterTableGeneratorSql(newTg, oldTg);

            // RENAME COLUMN이 UPDATE보다 먼저 (UPDATE는 new 컬럼명 사용)
            int renameIdx  = sql.indexOf("RENAME COLUMN");
            int updateIdx  = sql.indexOf("UPDATE");
            assertTrue(renameIdx >= 0 && updateIdx >= 0);
            assertTrue(renameIdx < updateIdx, "RENAME이 UPDATE보다 먼저 실행");

            // UPDATE는 이미 변경된 new 컬럼명 기준으로 작성
            assertTrue(sql.contains("\"new_pk\""), "UPDATE에 new 컬럼명 사용");
            assertTrue(sql.contains("\"new_val\""), "UPDATE에 new 값 컬럼명 사용");
        }

        private TableGeneratorModel tg(String table, String pkCol, String valCol,
                                       String pkVal, long initVal) {
            return TableGeneratorModel.builder()
                    .table(table).pkColumnName(pkCol).valueColumnName(valCol)
                    .pkColumnValue(pkVal).initialValue(initVal).build();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LiquibaseDialect — PG 타입명")
    class LiquibaseTypes {

        @Test @DisplayName("Boolean → BOOLEAN (MySQL의 BOOLEAN과 동일하나 default가 다름)")
        void booleanLiquibaseType() {
            assertEquals("BOOLEAN", newDialect().getLiquibaseTypeName(colModel("boolean")));
        }

        @Test @DisplayName("Double → DOUBLE PRECISION")
        void doubleLiquibaseType() {
            assertEquals("DOUBLE PRECISION", newDialect().getLiquibaseTypeName(colModel("java.lang.Double")));
        }

        @Test @DisplayName("Float → REAL")
        void floatLiquibaseType() {
            assertEquals("REAL", newDialect().getLiquibaseTypeName(colModel("java.lang.Float")));
        }

        @Test @DisplayName("UUID → uuid")
        void uuidLiquibaseType() {
            assertEquals("uuid", newDialect().getLiquibaseTypeName(colModel("java.util.UUID")));
        }

        @Test @DisplayName("byte[] LOB → BYTEA")
        void binaryLobLiquibaseType() {
            ColumnModel c = colModel("byte[]");
            when(c.isLob()).thenReturn(true);
            assertEquals("BYTEA", newDialect().getLiquibaseTypeName(c));
        }

        @Test @DisplayName("OffsetDateTime → TIMESTAMP WITH TIME ZONE")
        void offsetDateTimeLiquibaseType() {
            assertEquals("TIMESTAMP WITH TIME ZONE",
                    newDialect().getLiquibaseTypeName(colModel("java.time.OffsetDateTime")));
        }

        @Test @DisplayName("sqlTypeOverride가 있으면 그대로 반환")
        void sqlTypeOverride_usedDirectly() {
            ColumnModel c = colModel("java.lang.String");
            when(c.getSqlTypeOverride()).thenReturn("CITEXT");
            assertEquals("CITEXT", newDialect().getLiquibaseTypeName(c));
        }

        private ColumnModel colModel(String javaType) {
            ColumnModel c = mock(ColumnModel.class);
            when(c.getJavaType()).thenReturn(javaType);
            when(c.getConverterOutputType()).thenReturn(null);
            when(c.getSqlTypeOverride()).thenReturn(null);
            when(c.getLength()).thenReturn(255);
            when(c.getPrecision()).thenReturn(0);
            when(c.getScale()).thenReturn(0);
            when(c.isLob()).thenReturn(false);
            when(c.getEnumValues()).thenReturn(new String[]{});
            when(c.getTemporalType()).thenReturn(null);
            return c;
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private int countOccurrences(String str, String sub) {
        int count = 0, idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
