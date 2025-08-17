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

class DropTableBuilderTest {

    @Mock
    private Dialect dialect;

    private DropTableBuilder dropTableBuilder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dropTableBuilder = new DropTableBuilder(dialect);
    }

    // 테스트를 위한 간단한 DropTableContributor 구현체
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
    @DisplayName("빌더에 아무것도 추가하지 않았을 때, 빈 문자열을 빌드해야 한다")
    void build_whenNoContributors_returnsEmptyString() {
        // When
        String result = dropTableBuilder.build();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("단일 기여자를 추가했을 때, 해당 기여자의 SQL을 빌드해야 한다")
    void build_withSingleContributor_buildsCorrectSql() {
        // Given
        DropTableContributor contributor = new TestContributor(10, "DROP TABLE users;");
        dropTableBuilder.add(contributor);

        // When
        String result = dropTableBuilder.build();

        // Then
        assertThat(result).isEqualTo("DROP TABLE users;");
    }

    @Test
    @DisplayName("여러 기여자를 추가했을 때, 우선순위에 따라 정렬된 순서로 SQL을 빌드해야 한다")
    void build_withMultipleContributors_buildsSqlInPriorityOrder() {
        // Given
        // Mock 객체를 사용하여 contribute 메서드 호출 순서를 검증
        DropTableContributor highPriorityContributor = mock(DropTableContributor.class);
        DropTableContributor lowPriorityContributor = mock(DropTableContributor.class);

        when(highPriorityContributor.priority()).thenReturn(10); // 높은 우선순위 (숫자가 작음)
        when(lowPriorityContributor.priority()).thenReturn(100); // 낮은 우선순위 (숫자가 큼)

        // 우선순위가 낮은 것부터 추가하여 정렬이 잘 되는지 확인
        dropTableBuilder.add(lowPriorityContributor);
        dropTableBuilder.add(highPriorityContributor);

        // When
        dropTableBuilder.build();

        // Then
        // contribute() 메서드가 올바른 순서(우선순위가 높은 것부터)로 호출되었는지 검증
        InOrder inOrder = inOrder(highPriorityContributor, lowPriorityContributor);
        inOrder.verify(highPriorityContributor).contribute(any(StringBuilder.class), eq(dialect));
        inOrder.verify(lowPriorityContributor).contribute(any(StringBuilder.class), eq(dialect));
    }

    @Test
    @DisplayName("여러 기여자를 추가했을 때, 생성된 SQL 문자열이 올바른지 확인한다")
    void build_withMultipleContributors_buildsCorrectConcatenatedString() {
        // Given
        DropTableContributor dropConstraints = new TestContributor(10, "ALTER TABLE users DROP CONSTRAINT fk_user_role;");
        DropTableContributor dropTable = new TestContributor(100, "DROP TABLE users;");

        // 우선순위가 낮은 것부터 추가
        dropTableBuilder.add(dropTable);
        dropTableBuilder.add(dropConstraints);

        // When
        String result = dropTableBuilder.build();

        // Then
        String expectedSql = "ALTER TABLE users DROP CONSTRAINT fk_user_role;DROP TABLE users;";
        assertThat(result).isEqualTo(expectedSql);
    }
}