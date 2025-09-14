package org.jinx.migration.dialect.mysql;

import jakarta.persistence.TemporalType;
import org.jinx.migration.AbstractDialect;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySqlDialectTest {

    private MySqlDialect newDialect() {
        // 간단한 타입 매퍼: String → VARCHAR(n) (quotes), Integer/Long → INT/BIGINT (no quotes)
        JavaTypeMapper mapper = className -> new JavaTypeMapper.JavaType() {
            @Override public String getSqlType(int length, int precision, int scale) {
                return switch (className) {
                    case "java.lang.String" -> "VARCHAR(" + (length > 0 ? length : 255) + ")";
                    case "java.lang.Integer", "int" -> "INT";
                    case "java.lang.Long", "long" -> "BIGINT";
                    case "java.math.BigDecimal" -> "DECIMAL(" + precision + "," + scale + ")";
                    default -> "VARCHAR(" + (length > 0 ? length : 255) + ")";
                };
            }
            @Override public boolean needsQuotes() {
                return "java.lang.String".equals(className);
            }
            @Override public String getDefaultValue() {
                return switch (className) {
                    case "java.lang.Integer", "int" -> "0";
                    case "java.lang.Long", "long" -> "0";
                    case "java.lang.String" -> ""; // 빈 문자열 기본값
                    default -> null;
                };
            }
        };
        // needsQuotes=true면 작은따옴표 감싸기
        ValueTransformer vt = (value, type) -> type.needsQuotes() ? "'" + value + "'" : value;
        return new MySqlDialect(mapper, vt);
    }

    @Test @DisplayName("quoteIdentifier는 backtick으로 감싼다")
    void quoteIdentifier_backtick() {
        MySqlDialect d = newDialect();
        assertEquals("`users`", d.quoteIdentifier("users"));
    }

    @Test @DisplayName("PK 추가/정의/드랍(SQL 단순형)")
    void primaryKey_sqls() {
        MySqlDialect d = newDialect();
        assertEquals("ALTER TABLE `t` ADD PRIMARY KEY (`id`, `code`);\n",
                d.getAddPrimaryKeySql("t", List.of("id", "code")));
        assertEquals("PRIMARY KEY (`id`,`code`)".replace("`,`", "`, `"), // join 포맷 맞춤
                d.getPrimaryKeyDefinitionSql(List.of("id", "code")));
        assertEquals("ALTER TABLE `t` DROP PRIMARY KEY;\n", d.getDropPrimaryKeySql("t"));
    }

    @Test @DisplayName("AUTO_INCREMENT 포함 PK 드랍: 선행 MODIFY로 auto_increment 제거 후 DROP")
    void dropPrimaryKey_withAutoIncrementColumns() {
        MySqlDialect d = newDialect();

        ColumnModel id = mock(ColumnModel.class);
        when(id.isPrimaryKey()).thenReturn(true);
        when(id.getGenerationStrategy()).thenReturn(GenerationStrategy.IDENTITY);
        when(id.getJavaType()).thenReturn("java.lang.Long");
        when(id.getSqlTypeOverride()).thenReturn("BIGINT AUTO_INCREMENT");
        when(id.getLength()).thenReturn(0);
        when(id.getPrecision()).thenReturn(0);
        when(id.getScale()).thenReturn(0);
        when(id.isNullable()).thenReturn(false);
        when(id.getDefaultValue()).thenReturn(null);
        when(id.getColumnName()).thenReturn("id");

        String sql = d.getDropPrimaryKeySql("t", List.of(id));
        String expected =
                "ALTER TABLE `t` MODIFY COLUMN `id` BIGINT NOT NULL;\n" +
                        "ALTER TABLE `t` DROP PRIMARY KEY;\n";
        assertEquals(expected, sql);
    }

    @Test @DisplayName("컬럼 정의: 문자열/기본값/NOT NULL/ID/UUID 기본값/Temporal/LOB")
    void columnDefinition_variants() {
        MySqlDialect d = newDialect();

        ColumnModel name = mock(ColumnModel.class);
        when(name.getColumnName()).thenReturn("name");
        when(name.getJavaType()).thenReturn("java.lang.String");
        when(name.getLength()).thenReturn(50);
        when(name.getPrecision()).thenReturn(0);
        when(name.getScale()).thenReturn(0);
        when(name.isNullable()).thenReturn(false);
        when(name.getDefaultValue()).thenReturn("hi");
        when(name.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(name.isManualPrimaryKey()).thenReturn(false);
        when(name.isLob()).thenReturn(false);
        when(name.getConversionClass()).thenReturn(null);
        when(name.getSqlTypeOverride()).thenReturn(null);
        when(name.isVersion()).thenReturn(false);
        when(name.getTemporalType()).thenReturn(null);

        assertEquals("`name` VARCHAR(50) NOT NULL DEFAULT 'hi'", d.getColumnDefinitionSql(name));

        ColumnModel id = mock(ColumnModel.class);
        when(id.getColumnName()).thenReturn("id");
        when(id.getJavaType()).thenReturn("java.lang.Long");
        when(id.getLength()).thenReturn(0);
        when(id.getPrecision()).thenReturn(0);
        when(id.getScale()).thenReturn(0);
        when(id.isNullable()).thenReturn(true);
        when(id.getDefaultValue()).thenReturn(null);
        when(id.getGenerationStrategy()).thenReturn(GenerationStrategy.IDENTITY);
        when(id.isManualPrimaryKey()).thenReturn(false);
        when(id.isLob()).thenReturn(false);
        when(id.getConversionClass()).thenReturn(null);
        when(id.getSqlTypeOverride()).thenReturn("BIGINT"); // override에 AUTO_INCREMENT 없음 → 붙여야 함
        when(id.isVersion()).thenReturn(false);
        when(id.getTemporalType()).thenReturn(null);

        assertEquals("`id` BIGINT AUTO_INCREMENT", d.getColumnDefinitionSql(id));

        ColumnModel uuid = mock(ColumnModel.class);
        when(uuid.getColumnName()).thenReturn("uuid");
        when(uuid.getJavaType()).thenReturn("java.lang.String");
        when(uuid.getLength()).thenReturn(36);
        when(uuid.getPrecision()).thenReturn(0);
        when(uuid.getScale()).thenReturn(0);
        when(uuid.isNullable()).thenReturn(true);
        when(uuid.getDefaultValue()).thenReturn(null);
        when(uuid.getGenerationStrategy()).thenReturn(GenerationStrategy.UUID);
        when(uuid.isManualPrimaryKey()).thenReturn(false);
        when(uuid.isLob()).thenReturn(false);
        when(uuid.getConversionClass()).thenReturn(null);
        when(uuid.getSqlTypeOverride()).thenReturn(null);
        when(uuid.isVersion()).thenReturn(false);
        when(uuid.getTemporalType()).thenReturn(null);

        // UUID default는 함수 그대로(따옴표 없이)
        assertEquals("`uuid` VARCHAR(36) DEFAULT UUID()", d.getColumnDefinitionSql(uuid));

        ColumnModel dt = mock(ColumnModel.class);
        when(dt.getColumnName()).thenReturn("dt");
        when(dt.getJavaType()).thenReturn("java.util.Date");
        when(dt.getTemporalType()).thenReturn(TemporalType.TIMESTAMP);
        when(dt.getLength()).thenReturn(0);
        when(dt.getPrecision()).thenReturn(0);
        when(dt.getScale()).thenReturn(0);
        when(dt.isNullable()).thenReturn(true);
        when(dt.getDefaultValue()).thenReturn(null);
        when(dt.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(dt.isManualPrimaryKey()).thenReturn(false);
        when(dt.isLob()).thenReturn(false);
        when(dt.getConversionClass()).thenReturn(null);
        when(dt.getSqlTypeOverride()).thenReturn(null);
        when(dt.isVersion()).thenReturn(false);

        assertEquals("`dt` DATETIME", d.getColumnDefinitionSql(dt));

        ColumnModel lob = mock(ColumnModel.class);
        when(lob.getColumnName()).thenReturn("payload");
        when(lob.getJavaType()).thenReturn("java.lang.String");
        when(lob.isLob()).thenReturn(true);
        when(lob.getLength()).thenReturn(0);
        when(lob.getPrecision()).thenReturn(0);
        when(lob.getScale()).thenReturn(0);
        when(lob.isNullable()).thenReturn(true);
        when(lob.getDefaultValue()).thenReturn(null);
        when(lob.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(lob.isManualPrimaryKey()).thenReturn(false);
        when(lob.getConversionClass()).thenReturn(null);
        when(lob.getSqlTypeOverride()).thenReturn(null);
        when(lob.isVersion()).thenReturn(false);
        when(lob.getTemporalType()).thenReturn(null);

        assertEquals("`payload` TEXT DEFAULT ''", d.getColumnDefinitionSql(lob));
    }

    @Test @DisplayName("컬럼 ADD/DROP/MODIFY/RENAME SQL")
    void columnSqls() {
        MySqlDialect d = newDialect();

        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn("name");
        when(c.getJavaType()).thenReturn("java.lang.String");
        when(c.getLength()).thenReturn(10);
        when(c.getPrecision()).thenReturn(0);
        when(c.getScale()).thenReturn(0);
        when(c.isNullable()).thenReturn(true);
        when(c.getDefaultValue()).thenReturn(null);
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(c.isManualPrimaryKey()).thenReturn(false);
        when(c.isLob()).thenReturn(false);
        when(c.getConversionClass()).thenReturn(null);
        when(c.getSqlTypeOverride()).thenReturn(null);
        when(c.isVersion()).thenReturn(false);
        when(c.getTemporalType()).thenReturn(null);

        assertEquals("ALTER TABLE `t` ADD COLUMN `name` VARCHAR(10) DEFAULT '';\n", d.getAddColumnSql("t", c));
        assertEquals("ALTER TABLE `t` MODIFY COLUMN `name` VARCHAR(10) DEFAULT '';\n", d.getModifyColumnSql("t", c, c));
        assertEquals("ALTER TABLE `t` RENAME COLUMN `old` TO `new`;\n",
                d.getRenameColumnSql("t",
                        col("new", "java.lang.String"), col("old", "java.lang.String")));

        ColumnModel pkCol = mock(ColumnModel.class);
        when(pkCol.getColumnName()).thenReturn("id");
        when(pkCol.isPrimaryKey()).thenReturn(true);
        assertTrue(d.getDropColumnSql("t", pkCol).startsWith("ALTER TABLE `t` DROP PRIMARY KEY;\nALTER TABLE `t` DROP COLUMN `id`;\n"));
    }

    @Test @DisplayName("제약/인덱스 SQL")
    void constraint_index_sqls() {
        MySqlDialect d = newDialect();

        ConstraintModel unique = mock(ConstraintModel.class);
        when(unique.getType()).thenReturn(ConstraintType.UNIQUE);
        when(unique.getName()).thenReturn("uq_user_email");
        when(unique.getColumns()).thenReturn(List.of("email"));
        when(unique.getTableName()).thenReturn("users");
        assertEquals("CONSTRAINT `uq_user_email` UNIQUE (`email`)", d.getConstraintDefinitionSql(unique));
        assertEquals("ADD CONSTRAINT `uq_user_email` UNIQUE (`email`);\n", d.getAddConstraintSql("users", unique));
        assertEquals("ALTER TABLE `users` DROP KEY `uq_user_email`;\n", d.getDropConstraintSql("users", unique));

        ConstraintModel pk = mock(ConstraintModel.class);
        when(pk.getType()).thenReturn(ConstraintType.PRIMARY_KEY);
        when(pk.getName()).thenReturn("pk_users");
        when(pk.getColumns()).thenReturn(List.of("id"));
        when(pk.getTableName()).thenReturn("users");
        assertEquals("PRIMARY KEY (`id`)", d.getConstraintDefinitionSql(pk));
        assertEquals("ALTER TABLE `users` DROP PRIMARY KEY;\n",
                d.getDropConstraintSql("users", pk));

        IndexModel idx = mock(IndexModel.class);
        when(idx.getIndexName()).thenReturn("ix_users_email");
        when(idx.getColumnNames()).thenReturn(List.of("email"));
        assertEquals("CREATE INDEX `ix_users_email` ON `users` (`email`);\n", d.indexStatement(idx, "users"));
        assertEquals("DROP INDEX `ix_users_email` ON `users`;\n", d.getDropIndexSql("users", idx));

        IndexModel idx2 = mock(IndexModel.class);
        when(idx2.getIndexName()).thenReturn("ix_new");
        when(idx2.getColumnNames()).thenReturn(List.of("email"));
        assertEquals(
                "DROP INDEX `ix_users_email` ON `users`;\nCREATE INDEX `ix_new` ON `users` (`email`);\n",
                d.getModifyIndexSql("users", idx2, idx));
    }

    @Test @DisplayName("관계 SQL: NO_CONSTRAINT, 테이블 명시, ON DELETE/UPDATE, 복합 컬럼")
    void relationship_sqls() {
        MySqlDialect d = newDialect();

        RelationshipModel no = mock(RelationshipModel.class);
        when(no.isNoConstraint()).thenReturn(true);
        assertEquals("", d.getAddRelationshipSql("users", no));
        assertEquals("", d.getDropRelationshipSql("users", no));

        RelationshipModel rel = mock(RelationshipModel.class);
        when(rel.isNoConstraint()).thenReturn(false);
        when(rel.getTableName()).thenReturn("orders");
        when(rel.getConstraintName()).thenReturn(null);
        when(rel.getColumns()).thenReturn(List.of("user_id", "org_id"));
        when(rel.getReferencedTable()).thenReturn("users");
        when(rel.getReferencedColumns()).thenReturn(List.of("id", "org_id"));
        when(rel.getOnDelete()).thenReturn(OnDeleteAction.CASCADE);
        when(rel.getOnUpdate()).thenReturn(OnUpdateAction.NO_ACTION);

        String add = d.getAddRelationshipSql("ignored_table", rel);
        assertEquals(
                "ALTER TABLE `orders` ADD CONSTRAINT `fk_orders_user_id_org_id` FOREIGN KEY (`user_id`,`org_id`) REFERENCES `users` (`id`,`org_id`) ON DELETE CASCADE;\n",
                add
        );
        String drop = d.getDropRelationshipSql("ignored_table", rel);
        assertEquals("ALTER TABLE `orders` DROP FOREIGN KEY `fk_orders_user_id_org_id`;\n", drop);
    }

    @Test @DisplayName("TableGenerator SQL: CREATE/INSERT & DROP")
    void tableGenerator_sqls() {
        MySqlDialect d = newDialect();

        TableGeneratorModel tg = mock(TableGeneratorModel.class);
        when(tg.getTable()).thenReturn("seq_table");
        when(tg.getPkColumnName()).thenReturn("k");
        when(tg.getValueColumnName()).thenReturn("v");
        when(tg.getPkColumnValue()).thenReturn("SEQ_ORDER");
        when(tg.getInitialValue()).thenReturn(1L);

        String create = d.getCreateTableGeneratorSql(tg);
        assertEquals(
                "CREATE TABLE IF NOT EXISTS `seq_table` (`k` VARCHAR(255) NOT NULL PRIMARY KEY, `v` BIGINT NOT NULL);\n" +
                        "INSERT IGNORE INTO `seq_table` (`k`, `v`) VALUES ('SEQ_ORDER', 1);\n",
                create
        );

        assertEquals("DROP TABLE IF EXISTS `seq_table`;\n", d.getDropTableGeneratorSql(tg));
    }

    // --- helpers ---
    private ColumnModel col(String name, String javaType) {
        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn(name);
        when(c.getJavaType()).thenReturn(javaType);
        when(c.getLength()).thenReturn(10);
        when(c.getPrecision()).thenReturn(0);
        when(c.getScale()).thenReturn(0);
        when(c.isNullable()).thenReturn(true);
        when(c.getDefaultValue()).thenReturn(null);
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(c.isManualPrimaryKey()).thenReturn(false);
        when(c.isLob()).thenReturn(false);
        when(c.getConversionClass()).thenReturn(null);
        when(c.getSqlTypeOverride()).thenReturn(null);
        when(c.isVersion()).thenReturn(false);
        when(c.getTemporalType()).thenReturn(null);
        return c;
    }
}
