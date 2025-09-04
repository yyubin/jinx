package org.jinx.migration;

import org.jinx.migration.dialect.mysql.MySqlDialect;
import org.jinx.model.ColumnModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SqlTypeOverride DDL Generation")
class SqlTypeOverrideTest {

    private MySqlDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new MySqlDialect();
    }

    @Test
    @DisplayName("sqlTypeOverride가 있으면 길이/정밀도/스케일 무시하고 그대로 사용해야 한다")
    void getColumnDefinitionSql_withSqlTypeOverride_shouldUseOverrideDirectly() {
        // Arrange
        ColumnModel column = ColumnModel.builder()
                .columnName("test_column")
                .javaType("java.lang.String")
                .length(100)  // 무시되어야 함
                .precision(10) // 무시되어야 함
                .scale(2)     // 무시되어야 함
                .sqlTypeOverride("varchar(42)")
                .isNullable(false)
                .build();

        // Act
        String ddl = dialect.getColumnDefinitionSql(column);

        // Assert
        assertThat(ddl).contains("varchar(42)");
        assertThat(ddl).doesNotContain("VARCHAR(100)"); // 길이가 무시됨
        assertThat(ddl).contains("NOT NULL");
    }

    @Test
    @DisplayName("sqlTypeOverride가 null이면 기본 타입 매핑을 사용해야 한다")
    void getColumnDefinitionSql_withoutSqlTypeOverride_shouldUseDefaultMapping() {
        // Arrange
        ColumnModel column = ColumnModel.builder()
                .columnName("test_column")
                .javaType("java.lang.String")
                .length(100)
                .sqlTypeOverride(null)
                .isNullable(true)
                .build();

        // Act
        String ddl = dialect.getColumnDefinitionSql(column);

        // Assert
        assertThat(ddl).contains("VARCHAR(100)"); // 기본 매핑 사용
        assertThat(ddl).doesNotContain("NOT NULL");
    }

    @Test
    @DisplayName("Liquibase 타입 이름에서 sqlTypeOverride가 우선되어야 한다")
    void getLiquibaseTypeName_withSqlTypeOverride_shouldUseOverrideDirectly() {
        // Arrange
        ColumnModel column = ColumnModel.builder()
                .javaType("java.lang.String")
                .length(255)
                .sqlTypeOverride("char(3)")
                .build();

        // Act
        String typeName = dialect.getLiquibaseTypeName(column);

        // Assert
        assertThat(typeName).isEqualTo("char(3)");
    }

    @Test
    @DisplayName("Liquibase 타입 이름에서 sqlTypeOverride가 없으면 기본 매핑을 사용해야 한다")
    void getLiquibaseTypeName_withoutSqlTypeOverride_shouldUseDefaultMapping() {
        // Arrange
        ColumnModel column = ColumnModel.builder()
                .javaType("java.lang.String")
                .length(255)
                .sqlTypeOverride(null)
                .build();

        // Act
        String typeName = dialect.getLiquibaseTypeName(column);

        // Assert
        assertThat(typeName).isEqualTo("VARCHAR(255)");
    }

    @Test
    @DisplayName("sqlTypeOverride가 빈 문자열이면 기본 매핑을 사용해야 한다")
    void getLiquibaseTypeName_withEmptyOverride_shouldUseDefaultMapping() {
        // Arrange
        ColumnModel column = ColumnModel.builder()
                .javaType("java.lang.String")
                .length(255)
                .sqlTypeOverride("")
                .build();

        // Act
        String typeName = dialect.getLiquibaseTypeName(column);

        // Assert
        assertThat(typeName).isEqualTo("VARCHAR(255)");
    }

    @Test
    @DisplayName("PK 드랍 시 sqlTypeOverride가 있으면 오버라이드 타입을 유지해야 한다")
    void getDropPrimaryKeySql_withSqlTypeOverride_shouldPreserveOverrideType() {
        // Arrange
        ColumnModel identityColumn = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .generationStrategy(org.jinx.model.GenerationStrategy.IDENTITY)
                .sqlTypeOverride("bigint auto_increment")
                .isNullable(false)
                .build();

        // Act
        String dropSql = dialect.getDropPrimaryKeySql("test_table", List.of(identityColumn));

        // Assert
        assertThat(dropSql).contains("bigint auto_increment");
        assertThat(dropSql).doesNotContain("BIGINT"); // 기본 매핑이 사용되지 않아야 함
        assertThat(dropSql).contains("NOT NULL");
        assertThat(dropSql).contains("DROP PRIMARY KEY");
    }

    @Test
    @DisplayName("PK 드랍 시 sqlTypeOverride가 없으면 기본 매핑을 사용해야 한다")
    void getDropPrimaryKeySql_withoutSqlTypeOverride_shouldUseDefaultMapping() {
        // Arrange
        ColumnModel identityColumn = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .generationStrategy(org.jinx.model.GenerationStrategy.IDENTITY)
                .sqlTypeOverride(null)
                .isNullable(false)
                .build();

        // Act
        String dropSql = dialect.getDropPrimaryKeySql("test_table", List.of(identityColumn));

        // Assert
        assertThat(dropSql).contains("BIGINT"); // 기본 매핑 사용
        assertThat(dropSql).contains("NOT NULL");
        assertThat(dropSql).contains("DROP PRIMARY KEY");
    }

    @Test
    @DisplayName("getDropPrimaryKeySql은 sqlTypeOverride를 유지해야 한다")
    void getDropPrimaryKeySql_shouldPreserveSqlTypeOverride() {
        // Arrange
        ColumnModel col = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .length(20)
                .sqlTypeOverride("BIGINT(42)")
                .isNullable(false)
                .generationStrategy(org.jinx.model.GenerationStrategy.IDENTITY)
                .isPrimaryKey(true)
                .build();

        // Act
        String sql = dialect.getDropPrimaryKeySql("test_table", List.of(col));

        // Assert
        assertThat(sql).contains("MODIFY COLUMN `id` BIGINT(42)");
        assertThat(sql).contains("NOT NULL");
        assertThat(sql).contains("DROP PRIMARY KEY");
        assertThat(sql).doesNotContain("BIGINT NOT NULL"); // 기본 매핑이 사용되지 않아야 함
    }

    @Test
    @DisplayName("공백만 있는 sqlTypeOverride는 trim되어 기본 매핑을 사용해야 한다")
    void getDropPrimaryKeySql_withWhitespaceOnlyOverride_shouldUseDefaultMapping() {
        // Arrange
        ColumnModel col = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .length(20)
                .sqlTypeOverride("   ")  // 공백만 있는 경우
                .isNullable(false)
                .generationStrategy(org.jinx.model.GenerationStrategy.IDENTITY)
                .isPrimaryKey(true)
                .build();

        // Act
        String sql = dialect.getDropPrimaryKeySql("test_table", List.of(col));

        // Assert
        assertThat(sql).contains("MODIFY COLUMN `id` BIGINT"); // 기본 매핑 사용
        assertThat(sql).contains("NOT NULL");
        assertThat(sql).contains("DROP PRIMARY KEY");
    }

    @Test
    @DisplayName("sqlTypeOverride trim 정책: 앞뒤 공백은 제거되어야 한다")
    void getColumnDefinitionSql_shouldTrimSqlTypeOverride() {
        // Arrange
        ColumnModel column = ColumnModel.builder()
                .columnName("test_column")
                .javaType("java.lang.String")
                .sqlTypeOverride("  varchar(42)  ")  // 앞뒤 공백
                .isNullable(false)
                .build();

        // Act
        String ddl = dialect.getColumnDefinitionSql(column);

        // Assert
        assertThat(ddl).contains("varchar(42)");
        assertThat(ddl).doesNotContain("  varchar(42)  ");
        assertThat(ddl).contains("NOT NULL");
    }
}