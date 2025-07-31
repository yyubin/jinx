package org.jinx.migration.dialect.mysql;

import jakarta.persistence.TemporalType;
import org.jinx.migration.Dialect;
import org.jinx.migration.JavaTypeMapper;
import org.jinx.migration.ValueTransformer;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlDialectTest {

    private final Dialect dialect = new MySqlDialect();

    private static ColumnModel col(String name, String javaType, boolean pk, boolean nullable) {
        return ColumnModel.builder()
                .columnName(name)
                .javaType(javaType)
                .isPrimaryKey(pk)
                .isNullable(nullable)
                .build();
    }

    private static EntityModel userEntity() {
        ColumnModel id   = col("id", "java.lang.Integer", true, false);
        id.setGenerationStrategy(GenerationStrategy.IDENTITY);

        ColumnModel name = col("name", "java.lang.String", false, false);

        return EntityModel.builder()
                .entityName("com.example.User")
                .tableName("users")
                .columns(Map.of(
                        "id", id,
                        "name", name
                ))
                .build();
    }

    @Test @DisplayName("quoteIdentifier 는 back‑tick 으로 감싼다")
    void quoteIdentifier() {
        assertThat(dialect.quoteIdentifier("my_col")).isEqualTo("`my_col`");
    }

    @Test @DisplayName("getRenameTableSql 생성 형식 확인")
    void renameTableSql() {
        String sql = dialect.getRenameTableSql("old", "new");
        assertThat(sql.trim()).isEqualTo("ALTER TABLE `old` RENAME TO `new`;");
    }

    @Test @DisplayName("getDropTableSql(String) → IF EXISTS 포함")
    void dropTableIfExists() {
        String sql = dialect.getDropTableSql("users");
        assertThat(sql.trim()).isEqualTo("DROP TABLE IF EXISTS `users`;");
    }

    @Test @DisplayName("getDropTableSql(EntityModel) → 올바른 DROP TABLE 문 생성")
    void dropTableFromEntity() {
        EntityModel entity = EntityModel.builder().tableName("test_table").build();
        String sql = dialect.getDropTableSql(entity);
        assertThat(sql.trim()).isEqualTo("DROP TABLE IF EXISTS `test_table`;");
    }

    @Nested @DisplayName("CREATE TABLE 문 생성")
    class CreateTable {

        @Test @DisplayName("기본 컬럼 + PK정의 포함")
        void createTableSql() {
            String ddl = dialect.getCreateTableSql(userEntity());

            assertThat(ddl).contains("CREATE TABLE", "`users`");
            assertThat(ddl).contains("`id` INT", "AUTO_INCREMENT");
            assertThat(ddl).contains("`name` VARCHAR");
            assertThat(ddl).contains("PRIMARY KEY");
            assertThat(ddl.trim()).endsWith("utf8mb4_unicode_ci;");
        }
    }

    @Nested @DisplayName("복합 PK + AUTO_INCREMENT 순서 재정렬")
    class PrimaryKeyOrder {

        @Test @DisplayName("AUTO_INCREMENT 컬럼이 첫 번째로 이동")
        void reorderPrimaryKey() {
            ColumnModel id      = col("id",      "java.lang.Integer", true, false);
            id.setGenerationStrategy(GenerationStrategy.IDENTITY);
            ColumnModel tenant  = col("tenant",  "java.lang.String",  true, false);

            EntityModel e = EntityModel.builder()
                    .entityName("Sample")
                    .tableName("sample")
                    .columns(Map.of("tenant", tenant, "id", id))
                    .build();

            String ddl = dialect.getCreateTableSql(e);

            assertThat(ddl).containsPattern("PRIMARY KEY \\(`id`, `tenant`\\)");
        }
    }

    @Nested @DisplayName("컬럼 추가/삭제/수정 SQL")
    class AlterTable {

        @Test @DisplayName("ADD COLUMN SQL 생성")
        void addColumn() {
            String sql = dialect.getAddColumnSql("users", col("email","java.lang.String", false, true));
            assertThat(sql).contains("ALTER TABLE `users` ADD COLUMN");
            assertThat(sql).contains("`email` VARCHAR");
            assertThat(sql.trim()).endsWith(";");
        }

        @Test @DisplayName("컬럼 삭제 시 DROP COLUMN 포함")
        void dropColumn() {
            String sql = dialect.getDropColumnSql("users", col("name","java.lang.String", false, false));
            assertThat(sql).contains("ALTER TABLE `users` DROP COLUMN `name`");
        }

        @Test @DisplayName("컬럼 수정(MODIFY) SQL 생성")
        void modifyColumn() {
            ColumnModel oldC = col("name","java.lang.String", false, true);
            ColumnModel newC = col("name","java.lang.String", false, false);

            String sql = dialect.getModifyColumnSql("users", newC, oldC);

            assertThat(sql).contains("MODIFY COLUMN `name`");
            assertThat(sql).contains("NOT NULL");
        }

        @Test @DisplayName("컬럼 이름 변경(RENAME) SQL 생성")
        void renameColumn() {
            ColumnModel oldCol = col("user_name", "java.lang.String", false, true);
            ColumnModel newCol = col("username", "java.lang.String", false, true);
            String sql = dialect.getRenameColumnSql("users", newCol, oldCol);
            assertThat(sql.trim()).isEqualTo("ALTER TABLE `users` RENAME COLUMN `user_name` TO `username`;");
        }
    }

    @Nested @DisplayName("Primary Key 제약 조건")
    class PrimaryKeys {
        @Test @DisplayName("ADD PRIMARY KEY SQL 생성")
        void addPrimaryKey() {
            String sql = dialect.getAddPrimaryKeySql("users", List.of("id", "tenant_id"));
            assertThat(sql.trim()).isEqualTo("ALTER TABLE `users` ADD PRIMARY KEY (`id`, `tenant_id`);");
        }

        @Test @DisplayName("DROP PRIMARY KEY SQL 생성")
        void dropPrimaryKey() {
            String sql = dialect.getDropPrimaryKeySql("users");
            assertThat(sql.trim()).isEqualTo("ALTER TABLE `users` DROP PRIMARY KEY;");
        }
    }

    @Nested @DisplayName("일반 제약 조건 (UNIQUE, CHECK 등)")
    class Constraints {
        @Test @DisplayName("ADD CONSTRAINT (UNIQUE) SQL 생성")
        void addUniqueConstraint() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uk_email")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("email"))
                    .build();
            String sql = dialect.getAddConstraintSql("users", constraint);
            assertThat(sql.trim()).isEqualTo("ADD CONSTRAINT `uk_email` UNIQUE (`email`);");
        }

        @Test @DisplayName("DROP CONSTRAINT (UNIQUE) SQL 생성")
        void dropUniqueConstraint() {
            ConstraintModel constraint = ConstraintModel.builder().name("uk_email").type(ConstraintType.UNIQUE).build();
            String sql = dialect.getDropConstraintSql("users", constraint);
            assertThat(sql.trim()).isEqualTo("ALTER TABLE `users` DROP KEY `uk_email`;");
        }

        @Test @DisplayName("제약 조건 수정 시 DROP과 ADD 문을 모두 생성")
        void modifyConstraint() {
            ConstraintModel oldCons = ConstraintModel.builder().name("uk_email_old").type(ConstraintType.UNIQUE).build();
            ConstraintModel newCons = ConstraintModel.builder().name("uk_email_new").type(ConstraintType.UNIQUE).columns(List.of("email")).build();
            String sql = dialect.getModifyConstraintSql("users", newCons, oldCons);
            assertThat(sql).contains("DROP KEY `uk_email_old`");
            assertThat(sql).contains("ADD CONSTRAINT `uk_email_new`");
        }
    }

    @Nested @DisplayName("TableGenerator 스키마 객체")
    class TableGenerators {
        @Test @DisplayName("preSchemaObjects는 TableGenerator에 대한 CREATE와 INSERT 문을 생성")
        void preSchemaObjects() {
            TableGeneratorModel tg = TableGeneratorModel.builder()
                    .table("sequence_table")
                    .pkColumnName("seq_name")
                    .valueColumnName("seq_value")
                    .pkColumnValue("user_pk")
                    .initialValue(1000)
                    .build();
            SchemaModel schema = SchemaModel.builder().tableGenerators(Map.of("user_gen", tg)).build();

            String sql = dialect.preSchemaObjects(schema);

            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `sequence_table`");
            // valueTransformer가 따옴표를 추가하므로, 테스트에서는 따옴표가 없는 값을 기대해야 함
            assertThat(sql).contains("INSERT IGNORE INTO `sequence_table` (`seq_name`, `seq_value`) VALUES ('user_pk', 1000);");
        }
    }

    @Nested @DisplayName("getColumnDefinitionSql – 타입 매핑 분기")
    class ColumnTypeMapping {

        @Test @DisplayName("BigDecimal → DECIMAL(precision,scale)")
        void decimalMapping() {
            ColumnModel price = ColumnModel.builder()
                    .columnName("price")
                    .javaType("java.math.BigDecimal")
                    .precision(12)
                    .scale(4)
                    .isNullable(true)
                    .build();
            String sql = dialect.getColumnDefinitionSql(price);
            assertThat(sql).contains("`price`").contains("DECIMAL(12,4)");
        }

        @Test @DisplayName("LOB(String) → TEXT")
        void lobStringMapping() {
            ColumnModel desc = ColumnModel.builder()
                    .columnName("description")
                    .javaType("java.lang.String")
                    .isLob(true)
                    .isNullable(true)
                    .build();
            String sql = dialect.getColumnDefinitionSql(desc);
            assertThat(sql).contains("TEXT");
        }

        @Test @DisplayName("Temporal DATE / TIME / TIMESTAMP")
        void temporalMappings() {
            ColumnModel d = col("d", "java.time.LocalDate", false, true);
            d.setTemporalType(TemporalType.DATE);
            ColumnModel t = col("t", "java.time.LocalTime", false, true);
            t.setTemporalType(TemporalType.TIME);
            ColumnModel ts = col("ts", "java.time.LocalDateTime", false, true);
            ts.setTemporalType(TemporalType.TIMESTAMP);

            assertThat(dialect.getColumnDefinitionSql(d)).contains("DATE");
            assertThat(dialect.getColumnDefinitionSql(t)).contains("TIME");
            assertThat(dialect.getColumnDefinitionSql(ts)).contains("DATETIME");
        }
    }

    @Nested @DisplayName("ALTER TABLE – UNIQUE / INDEX")
    class UniqueAndIndex {

        @Test @DisplayName("isUnique=true 컬럼 추가 시 ADD COLUMN + ADD UNIQUE INDEX 두 단계 발생")
        void addUniqueColumnGeneratesTwoStatements() {
            ColumnModel email = ColumnModel.builder()
                    .columnName("email")
                    .javaType("java.lang.String")
                    .isUnique(true)
                    .build();
            String sql = dialect.getAddColumnSql("users", email);
            assertThat(sql)
                    .contains("ADD COLUMN")
                    .contains("`email`")
                    .contains("ADD UNIQUE INDEX `uk_users_email`");
        }

        @Test @DisplayName("INDEX 수정 시 DROP INDEX + CREATE INDEX 순 생성")
        void modifyIndexDropThenAdd() {
            IndexModel oldIdx = IndexModel.builder()
                    .indexName("idx_name")
                    .columnNames(List.of("name"))
                    .isUnique(false)
                    .build();
            IndexModel newIdx = IndexModel.builder()
                    .indexName("idx_name")
                    .columnNames(List.of("name"))
                    .isUnique(true)
                    .build();
            String sql = dialect.getModifyIndexSql("users", newIdx, oldIdx);
            assertThat(sql)
                    .contains("DROP INDEX `idx_name`")
                    .contains("CREATE UNIQUE INDEX `idx_name`");
        }
    }

    @Nested @DisplayName("FOREIGN KEY – ON DELETE / UPDATE 옵션")
    class RelationshipFk {

        @Test @DisplayName("ON DELETE CASCADE & ON UPDATE RESTRICT 포함")
        void fkWithCascadeAndRestrict() {
            RelationshipModel fk = RelationshipModel.builder()
                    .column("role_id")
                    .referencedTable("role")
                    .referencedColumn("id")
                    .onDelete(OnDeleteAction.CASCADE)
                    .onUpdate(OnUpdateAction.RESTRICT)
                    .build();
            String sql = dialect.getAddRelationshipSql("user_role", fk);
            assertThat(sql)
                    .contains("FOREIGN KEY (`role_id`)")
                    .contains("REFERENCES `role` (`id`)")
                    .contains("ON DELETE CASCADE")
                    .contains("ON UPDATE RESTRICT");
        }

        @Test @DisplayName("관계 삭제 시 DROP FOREIGN KEY 문 생성")
        void dropRelationship() {
            RelationshipModel fk = RelationshipModel.builder()
                    .constraintName("fk_user_role")
                    .column("role_id")
                    .build();
            String sql = dialect.getDropRelationshipSql("user_role", fk);
            assertThat(sql.trim()).isEqualTo("ALTER TABLE `user_role` DROP FOREIGN KEY `fk_user_role`;");
        }

        @Test @DisplayName("관계 수정 시 DROP과 ADD 문을 모두 생성")
        void modifyRelationship() {
            RelationshipModel oldRel = RelationshipModel.builder().constraintName("fk_old").column("role_id").build();
            RelationshipModel newRel = RelationshipModel.builder().constraintName("fk_new").column("role_id").referencedTable("roles").referencedColumn("id").build();
            String sql = dialect.getModifyRelationshipSql("user_role", newRel, oldRel);
            assertThat(sql).contains("DROP FOREIGN KEY `fk_old`");
            assertThat(sql).contains("ADD CONSTRAINT `fk_new`");
        }
    }

    // --- 추가된 테스트 케이스들 ---
    @Nested
    @DisplayName("추가 커버리지 테스트")
    class CoverageIncreaseTests {

        @Test
        @DisplayName("[getDropPrimaryKeySql(String, Collection)] IDENTITY 컬럼의 AUTO_INCREMENT 속성을 제거")
        void dropPrimaryKeyRemovesAutoIncrement() {
            ColumnModel idCol = col("id", "java.lang.Integer", true, false);
            idCol.setGenerationStrategy(GenerationStrategy.IDENTITY);

            String sql = dialect.getDropPrimaryKeySql("users", List.of(idCol));

            assertThat(sql)
                    .startsWith("ALTER TABLE `users` MODIFY COLUMN `id` INT NOT NULL;")
                    .endsWith("ALTER TABLE `users` DROP PRIMARY KEY;\n");
        }

        @Test
        @DisplayName("[getModifyColumnSql] UNIQUE 속성 추가/삭제 시 관련 INDEX 문 생성")
        void modifyColumnHandlesUniqueChange() {
            ColumnModel oldCol = col("email", "java.lang.String", false, true);
            oldCol.setUnique(false);
            ColumnModel newCol = col("email", "java.lang.String", false, true);
            newCol.setUnique(true);

            String sql = dialect.getModifyColumnSql("users", newCol, oldCol);

            assertThat(sql)
                    .contains("MODIFY COLUMN `email`")
                    .contains("ADD UNIQUE INDEX `uk_users_email`");

            // 반대의 경우 (unique -> non-unique)
            sql = dialect.getModifyColumnSql("users", oldCol, newCol);
            assertThat(sql)
                    .contains("DROP INDEX `uk_users_email`")
                    .contains("MODIFY COLUMN `email`");
        }

        @Test
        @DisplayName("[getConstraintDefinitionSql] CHECK, PRIMARY_KEY 제약 조건 정의")
        void constraintDefinitionForCheckAndPk() {
            ConstraintModel check = ConstraintModel.builder().name("chk_age").type(ConstraintType.CHECK).checkClause("age > 18").build();
            ConstraintModel pk = ConstraintModel.builder().name("pk_users").type(ConstraintType.PRIMARY_KEY).columns(List.of("id", "tenant_id")).build();

            String checkSql = dialect.getConstraintDefinitionSql(check);
            String pkSql = dialect.getConstraintDefinitionSql(pk);

            assertThat(checkSql).contains("CONSTRAINT `chk_age` CHECK (age > 18)");
            assertThat(pkSql).isEqualTo("PRIMARY KEY (`id`, `tenant_id`)");
        }

        @Test
        @DisplayName("[getDropConstraintSql] CHECK, PRIMARY_KEY 제약 조건 삭제")
        void dropConstraintForCheckAndPk() {
            ConstraintModel check = ConstraintModel.builder().name("chk_age").type(ConstraintType.CHECK).build();
            ConstraintModel pk = ConstraintModel.builder().name("pk_users").type(ConstraintType.PRIMARY_KEY).build();

            String checkSql = dialect.getDropConstraintSql("users", check);
            String pkSql = dialect.getDropConstraintSql("users", pk);

            assertThat(checkSql).contains("DROP CHECK `chk_age`");
            assertThat(pkSql).contains("DROP PRIMARY KEY");
        }

        @Test
        @DisplayName("[getAlterTableSql] Visitor를 통해 SQL이 생성되는지 확인 (간단한 통합 테스트)")
        void alterTableSqlUsesVisitor() {
            DiffResult.ModifiedEntity modified = DiffResult.ModifiedEntity.builder()
                    .oldEntity(userEntity())
                    .newEntity(userEntity())
                    .build();

            // 컬럼 추가 Diff 생성
            modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                    .type(DiffResult.ColumnDiff.Type.ADDED)
                    .column(col("email", "java.lang.String", false, true))
                    .build());

            String sql = dialect.getAlterTableSql(modified);

            assertThat(sql).contains("ALTER TABLE `users` ADD COLUMN `email` VARCHAR(255)");
        }

        @Test
        @DisplayName("[Constructor] 테스트용 생성자 커버")
        void testOnlyConstructor() {
            Dialect testDialect = new MySqlDialect(mock(JavaTypeMapper.class), mock(ValueTransformer.class));
            assertThat(testDialect).isNotNull();
        }

        @Test
        @DisplayName("[getAddPrimaryKeySql] null 또는 빈 컬럼 리스트는 빈 문자열 반환")
        void addPrimaryKeyWithNullOrEmptyColumns() {
            assertThat(dialect.getAddPrimaryKeySql("users", null)).isEmpty();
            assertThat(dialect.getAddPrimaryKeySql("users", Collections.emptyList())).isEmpty();
        }

        @Test
        @DisplayName("[getColumnDefinitionSql] 특수 케이스 타입 매핑")
        void columnDefinitionForSpecialCases() {
            // @Convert 사용 시
            ColumnModel cc = col("data", "com.example.MyType", false, true);
            cc.setConversionClass("java.lang.String");
            assertThat(dialect.getColumnDefinitionSql(cc)).contains("VARCHAR(255)");

            // LOB (byte[])
            ColumnModel blob = col("data", "byte[]", false, true);
            blob.setLob(true);
            assertThat(dialect.getColumnDefinitionSql(blob)).contains("BLOB");

            // @Version
            ColumnModel verLong = col("version", "java.lang.Long", false, false);
            verLong.setVersion(true);
            assertThat(dialect.getColumnDefinitionSql(verLong)).contains("BIGINT");

            // isManualPrimaryKey
            ColumnModel manualPk = col("id", "java.lang.String", true, false);
            manualPk.setManualPrimaryKey(true);
            assertThat(dialect.getColumnDefinitionSql(manualPk)).endsWith("PRIMARY KEY");

            // defaultValue
            ColumnModel defaultCol = col("status", "java.lang.String", false, false);
            defaultCol.setDefaultValue("ACTIVE");
            assertThat(dialect.getColumnDefinitionSql(defaultCol)).contains("DEFAULT 'ACTIVE'");
        }

        @Test
        @DisplayName("[getDropColumnSql] UNIQUE 또는 PK 컬럼 삭제")
        void dropUniqueOrPkColumn() {
            // UNIQUE 컬럼 삭제
            ColumnModel uniqueCol = col("email", "java.lang.String", false, false);
            uniqueCol.setUnique(true);
            String sqlUnique = dialect.getDropColumnSql("users", uniqueCol);
            assertThat(sqlUnique)
                    .contains("DROP INDEX `uk_users_email`")
                    .contains("DROP COLUMN `email`");

            // PK 컬럼 삭제
            ColumnModel pkCol = col("id", "java.lang.Integer", true, false);
            String sqlPk = dialect.getDropColumnSql("users", pkCol);
            assertThat(sqlPk)
                    .contains("DROP PRIMARY KEY")
                    .contains("DROP COLUMN `id`");
        }

        @Test
        @DisplayName("[getAddRelationshipSql] ON DELETE/UPDATE 기본값(NO_ACTION)은 SQL에 미포함")
        void addRelationshipWithDefaultActions() {
            RelationshipModel fk = RelationshipModel.builder()
                    .column("role_id").referencedTable("role").referencedColumn("id")
                    .onDelete(OnDeleteAction.NO_ACTION) // 무시되어야 함
                    .onUpdate(null) // 무시되어야 함
                    .build();
            String sql = dialect.getAddRelationshipSql("user_role", fk);
            assertThat(sql)
                    .doesNotContain("ON DELETE")
                    .doesNotContain("ON UPDATE");
        }

        @Test
        @DisplayName("[getDropRelationshipSql] 제약조건 이름이 null일 때 자동 생성")
        void dropRelationshipWithNullConstraintName() {
            RelationshipModel fk = RelationshipModel.builder()
                    .constraintName(null)
                    .column("company_id")
                    .build();
            String sql = dialect.getDropRelationshipSql("users", fk);
            assertThat(sql).contains("DROP FOREIGN KEY `fk_users_company_id`");
        }

        @Test
        @DisplayName("[indexStatement] 고유하지 않은 인덱스 생성")
        void nonUniqueIndexStatement() {
            IndexModel idx = IndexModel.builder().indexName("idx_name").columnNames(List.of("name")).isUnique(false).build();
            String sql = dialect.indexStatement(idx, "users");
            assertThat(sql).startsWith("CREATE INDEX");
        }

        @Test
        @DisplayName("[getDropPrimaryKeySql(String, Collection)] IDENTITY 컬럼의 AUTO_INCREMENT 속성을 제거")
        void dropPrimaryKeyRemovesAutoIncrement_() {
            ColumnModel idCol = col("id", "java.lang.Integer", true, false);
            idCol.setGenerationStrategy(GenerationStrategy.IDENTITY);

            String sql = dialect.getDropPrimaryKeySql("users", List.of(idCol));

            assertThat(sql)
                    .startsWith("ALTER TABLE `users` MODIFY COLUMN `id` INT NOT NULL;")
                    .endsWith("ALTER TABLE `users` DROP PRIMARY KEY;\n");
        }
    }
}
