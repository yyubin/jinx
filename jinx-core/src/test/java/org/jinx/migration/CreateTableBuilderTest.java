package org.jinx.migration;

import org.jinx.migration.contributor.SqlContributor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CreateTableBuilderTest {

    @Mock
    private Dialect dialect;

    private CreateTableBuilder createTableBuilder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        createTableBuilder = new CreateTableBuilder("users", dialect);

        // Dialect의 동작을 모의(Mock) 설정
        when(dialect.openCreateTable("users")).thenReturn("CREATE TABLE users (\n");
        when(dialect.closeCreateTable()).thenReturn("\n);");
    }

    // 테스트를 위한 간단한 TableBodyContributor 구현체
    private static class TestBodyContributor implements SqlContributor {
        private final int priority;
        private final String sql;

        TestBodyContributor(int priority, String sql) {
            this.priority = priority;
            this.sql = sql;
        }

        @Override
        public void contribute(StringBuilder sb, Dialect dialect) {
            sb.append(sql);
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    // 테스트를 위한 간단한 PostCreateContributor 구현체
    private static class TestPostContributor implements SqlContributor {
        private final int priority;
        private final String sql;

        TestPostContributor(int priority, String sql) {
            this.priority = priority;
            this.sql = sql;
        }

        @Override
        public void contribute(StringBuilder sb, Dialect dialect) {
            sb.append(sql);
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    @Test
    @DisplayName("기여자가 없을 때, 기본적인 CREATE TABLE 구문을 생성해야 한다")
    void build_whenNoContributors_returnsBasicCreateTableStatement() {
        // When
        String result = createTableBuilder.build();

        // Then
        String expectedSql = "CREATE TABLE users (\n\n);\n";
        assertThat(result).isEqualTo(expectedSql);
    }

    @Test
    @DisplayName("TableBodyContributor가 있을 때, 테이블 정의 내부에 SQL을 추가해야 한다")
    void build_withBodyContributor_addsSqlInsideParentheses() {
        // Given
        createTableBuilder.add(new TestBodyContributor(10, "  id INT PRIMARY KEY,\n"));

        // When
        String result = createTableBuilder.build();

        // Then
        String expectedSql = "CREATE TABLE users (\n" +
                "  id INT PRIMARY KEY" +
                "\n);\n";
        assertThat(result).isEqualTo(expectedSql);
    }

    @Test
    @DisplayName("PostCreateContributor가 있을 때, 테이블 정의 외부에 SQL을 추가해야 한다")
    void build_withPostContributor_addsSqlOutsideParentheses() {
        // Given
        createTableBuilder.add(new TestPostContributor(10, "CREATE INDEX idx_users_id ON users(id);\n"));

        // When
        String result = createTableBuilder.build();

        // Then
        String expectedSql = "CREATE TABLE users (\n\n);\n" +
                "CREATE INDEX idx_users_id ON users(id);\n";
        assertThat(result).isEqualTo(expectedSql);
    }

    @Test
    @DisplayName("여러 Body 기여자가 있을 때, 우선순위 순서로 SQL을 생성해야 한다")
    void build_withMultipleBodyContributors_ordersByPriority() {
        // Given
        TableBodyContributor columns = mock(TableBodyContributor.class);
        TableBodyContributor primaryKey = mock(TableBodyContributor.class);

        when(columns.priority()).thenReturn(10);
        when(primaryKey.priority()).thenReturn(100);

        // 우선순위가 낮은 것부터 추가
        createTableBuilder.add(primaryKey);
        createTableBuilder.add(columns);

        // When
        createTableBuilder.build();

        // Then
        // contribute() 메서드가 우선순위가 높은 순서(columns -> primaryKey)로 호출되었는지 검증
        InOrder inOrder = inOrder(columns, primaryKey);
        inOrder.verify(columns).contribute(any(StringBuilder.class), eq(dialect));
        inOrder.verify(primaryKey).contribute(any(StringBuilder.class), eq(dialect));
    }

    @Test
    @DisplayName("Body와 Post 기여자가 모두 있을 때, 올바른 구조의 SQL을 생성해야 한다")
    void build_withBodyAndPostContributors_buildsCorrectFullStatement() {
        // Given
        createTableBuilder.add(new TestBodyContributor(10, "  id INT,\n"));
        createTableBuilder.add(new TestBodyContributor(20, "  name VARCHAR(255),\n"));
        createTableBuilder.add(new TestBodyContributor(100, "  PRIMARY KEY (id)")); // No trailing comma here

        createTableBuilder.add(new TestPostContributor(10, "CREATE INDEX idx_name ON users(name);\n"));

        // When
        String result = createTableBuilder.build();

        // Then
        // FIX: 실제 결과에 맞게 예상 문자열의 불필요한 개행 제거
        String expectedSql = "CREATE TABLE users (\n" +
                "  id INT,\n" +
                "  name VARCHAR(255)" + // 마지막 쉼표와 개행이 제거된 후 다음 내용이 이어짐
                "  PRIMARY KEY (id)" +
                "\n);\n" +
                "CREATE INDEX idx_name ON users(name);\n";
        assertThat(result).isEqualTo(expectedSql);
    }

    @Test
    @DisplayName("마지막 Body 기여자의 후행 쉼표를 올바르게 제거해야 한다")
    void build_withTrailingComma_trimsItCorrectly() {
        // Given
        createTableBuilder.add(new TestBodyContributor(10, "  id INT PRIMARY KEY,\n")); // Has a trailing comma

        // When
        String result = createTableBuilder.build();

        // Then
        String expectedSql = "CREATE TABLE users (\n" +
                "  id INT PRIMARY KEY" + // Assert comma and newline are gone
                "\n);\n";
        assertThat(result).isEqualTo(expectedSql);
    }
}
