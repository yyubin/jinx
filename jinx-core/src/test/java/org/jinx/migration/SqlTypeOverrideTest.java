package org.jinx.migration;

import org.jinx.migration.dialect.mysql.MySqlDialect;
import org.jinx.model.ColumnModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}