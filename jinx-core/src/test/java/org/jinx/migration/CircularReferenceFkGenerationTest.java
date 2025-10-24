package org.jinx.migration;

import org.jinx.migration.differs.SchemaDiffer;
import org.jinx.migration.dialect.mysql.MySqlDialect;
import org.jinx.model.ColumnKey;
import org.jinx.model.ColumnModel;
import org.jinx.model.DialectBundle;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CircularReferenceFkGenerationTest {

    @Test
    void testCircularReferenceFkGeneration() {
        // 1. 스키마 설정
        SchemaModel oldSchema = SchemaModel.builder().build();
        SchemaModel newSchema = buildNewSchema();

        // 2. Diff 생성
        SchemaDiffer differ = new SchemaDiffer();
        var diff = differ.diff(oldSchema, newSchema);

        // 3. SQL 생성
        var ddlDialect = new MySqlDialect();
        var dialects = DialectBundle.builder(ddlDialect, DatabaseType.MYSQL).build();
        MigrationGenerator generator = new MigrationGenerator(dialects, newSchema, false);
        String sql = generator.generateSql(diff);

        System.out.println(sql);

        // 4. 검증
        assertThat(sql).isNotBlank();

        int createTableUserAccountPos = sql.indexOf("CREATE TABLE `USER_ACCOUNT`");
        int createTableUserProfilePos = sql.indexOf("CREATE TABLE `USER_PROFILE`");
        int alterTableUserAccountPos = sql.indexOf("ALTER TABLE `USER_ACCOUNT`");
        int alterTableUserProfilePos = sql.indexOf("ALTER TABLE `USER_PROFILE`");

        // CREATE TABLE 문이 먼저 나와야 함
        assertThat(createTableUserAccountPos).isLessThan(alterTableUserAccountPos);
        assertThat(createTableUserProfilePos).isLessThan(alterTableUserProfilePos);
        assertThat(createTableUserAccountPos).isLessThan(alterTableUserAccountPos);
        assertThat(createTableUserProfilePos).isLessThan(alterTableUserProfilePos);

        // ALTER TABLE 문에는 FOREIGN KEY가 있어야 함
        assertThat(sql.substring(alterTableUserAccountPos)).contains("FOREIGN KEY (`main_profile_id`) REFERENCES `USER_PROFILE` (`id`)");
        assertThat(sql.substring(alterTableUserProfilePos)).contains("FOREIGN KEY (`user_account_id`) REFERENCES `USER_ACCOUNT` (`id`)");
    }

    private SchemaModel buildNewSchema() {
        // USER_ACCOUNT 엔티티
        EntityModel userAccount = EntityModel.builder()
            .tableName("USER_ACCOUNT")
            .columns(Map.of(
                ColumnKey.of("USER_ACCOUNT", "id"), ColumnModel.builder().columnName("id").javaType("int").sqlTypeOverride("BIGINT").isPrimaryKey(true).isNullable(false).build(),
                ColumnKey.of("USER_ACCOUNT", "main_profile_id"), ColumnModel.builder().columnName("main_profile_id").javaType("int").sqlTypeOverride("BIGINT").build()
            ))
            .relationships(Map.of(
                "fk_user_account_on_main_profile", RelationshipModel.builder()
                    .constraintName("fk_user_account_on_main_profile")
                    .columns(List.of("main_profile_id"))
                    .referencedTable("USER_PROFILE")
                    .referencedColumns(List.of("id"))
                    .build()
            ))
            .build();

        // USER_PROFILE 엔티티
        EntityModel userProfile = EntityModel.builder()
            .tableName("USER_PROFILE")
            .columns(Map.of(
                ColumnKey.of("USER_PROFILE", "id"), ColumnModel.builder().columnName("id").javaType("int").sqlTypeOverride("BIGINT").isPrimaryKey(true).isNullable(false).build(),
                ColumnKey.of("USER_PROFILE", "user_account_id"), ColumnModel.builder().columnName("user_account_id").javaType("int").sqlTypeOverride("BIGINT").isNullable(false).build()
            ))
            .relationships(Map.of(
                "fk_user_profile_on_user_account", RelationshipModel.builder()
                    .constraintName("fk_user_profile_on_user_account")
                    .columns(List.of("user_account_id"))
                    .referencedTable("USER_ACCOUNT")
                    .referencedColumns(List.of("id"))
                    .build()
            ))
            .build();

        return SchemaModel.builder()
            .entities(Map.of(
                "USER_ACCOUNT", userAccount,
                "USER_PROFILE", userProfile
            ))
            .build();
    }
}
