package org.jinx.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AlterTableBuilderTest {

    @Mock
    private Dialect dialect;

    private AlterTableBuilder alterTableBuilder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        alterTableBuilder = new AlterTableBuilder("users", dialect);
    }

    // 테스트를 위한 간단한 SqlContributor 구현체
    private static class TestContributor implements SqlContributor {
        private final int priority;
        private final String sql;

        TestContributor(int priority, String sql) {
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
    @DisplayName("기여자가 없을 때, 빈 문자열을 빌드해야 한다")
    void build_whenNoContributors_returnsEmptyString() {
        // When
        String result = alterTableBuilder.build();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("단일 기여자를 추가했을 때, 해당 기여자의 SQL을 빌드해야 한다")
    void build_withSingleContributor_buildsCorrectSql() {
        // Given
        alterTableBuilder.add(new TestContributor(10, "ALTER TABLE users ADD COLUMN email VARCHAR(255);"));

        // When
        String result = alterTableBuilder.build();

        // Then
        assertThat(result).isEqualTo("ALTER TABLE users ADD COLUMN email VARCHAR(255);");
    }

    @Test
    @DisplayName("여러 기여자를 추가했을 때, 우선순위에 따라 정렬된 순서로 SQL을 빌드해야 한다")
    void build_withMultipleContributors_buildsSqlInPriorityOrder() {
        // Given
        SqlContributor addColumn = mock(SqlContributor.class);
        SqlContributor dropColumn = mock(SqlContributor.class);

        when(addColumn.priority()).thenReturn(100); // 낮은 우선순위
        when(dropColumn.priority()).thenReturn(10);  // 높은 우선순위

        // 우선순위가 낮은 것부터 추가
        alterTableBuilder.add(addColumn);
        alterTableBuilder.add(dropColumn);

        // When
        alterTableBuilder.build();

        // Then
        // contribute() 메서드가 우선순위가 높은 순서(dropColumn -> addColumn)로 호출되었는지 검증
        InOrder inOrder = inOrder(dropColumn, addColumn);
        inOrder.verify(dropColumn).contribute(any(StringBuilder.class), eq(dialect));
        inOrder.verify(addColumn).contribute(any(StringBuilder.class), eq(dialect));
    }

    @Test
    @DisplayName("여러 기여자를 추가했을 때, 생성된 SQL 문자열이 올바른지 확인한다")
    void build_withMultipleContributors_buildsCorrectConcatenatedString() {
        // Given
        SqlContributor addColumn = new TestContributor(100, "ALTER TABLE users ADD COLUMN name VARCHAR(255);");
        SqlContributor dropColumn = new TestContributor(10, "ALTER TABLE users DROP COLUMN username;");

        // 우선순위가 낮은 것부터 추가
        alterTableBuilder.add(addColumn);
        alterTableBuilder.add(dropColumn);

        // When
        String result = alterTableBuilder.build();

        // Then
        String expectedSql = "ALTER TABLE users DROP COLUMN username;ALTER TABLE users ADD COLUMN name VARCHAR(255);";
        assertThat(result).isEqualTo(expectedSql);
    }

    @Test
    @DisplayName("빌드된 SQL의 앞뒤 공백을 제거해야 한다")
    void build_withLeadingAndTrailingWhitespace_trimsTheResult() {
        // Given
        alterTableBuilder.add(new TestContributor(10, "  ALTER TABLE users ADD COLUMN email VARCHAR(255);  "));

        // When
        String result = alterTableBuilder.build();

        // Then
        assertThat(result).isEqualTo("ALTER TABLE users ADD COLUMN email VARCHAR(255);");
    }
}