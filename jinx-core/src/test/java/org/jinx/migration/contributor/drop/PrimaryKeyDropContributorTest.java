package org.jinx.migration.contributor.drop;

import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrimaryKeyDropContributorTest {

    private DdlDialect mockDialect;
    private StringBuilder stringBuilder;

    @BeforeEach
    void setUp() {
        mockDialect = mock(DdlDialect.class);
        stringBuilder = new StringBuilder();
    }

    @Nested
    @DisplayName("기본 동작")
    class BasicBehaviorTests {

        @Test
        @DisplayName("priority는 10을 반환한다")
        void priorityReturns10() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            assertEquals(10, contributor.priority());
        }

        @Test
        @DisplayName("contribute는 dialect의 getDropPrimaryKeySql을 호출한다")
        void contributeCallsDialectMethod() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            verify(mockDialect, times(1)).getDropPrimaryKeySql("users", columns);
            assertEquals("ALTER TABLE users DROP PRIMARY KEY", stringBuilder.toString());
        }

        @Test
        @DisplayName("table과 currentColumns를 올바르게 전달한다")
        void correctlyPassesParameters() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build(),
                    ColumnModel.builder().columnName("tenant_id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("orders", columns);

            when(mockDialect.getDropPrimaryKeySql("orders", columns))
                    .thenReturn("ALTER TABLE orders DROP CONSTRAINT pk_orders");

            contributor.contribute(stringBuilder, mockDialect);

            verify(mockDialect).getDropPrimaryKeySql("orders", columns);
            assertEquals("ALTER TABLE orders DROP CONSTRAINT pk_orders", stringBuilder.toString());
        }
    }

    @Nested
    @DisplayName("Record 특성")
    class RecordCharacteristicsTests {

        @Test
        @DisplayName("table() 메서드는 테이블명을 반환한다")
        void tableMethodReturnsTableName() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("products", columns);

            assertEquals("products", contributor.table());
        }

        @Test
        @DisplayName("currentColumns() 메서드는 컬럼 컬렉션을 반환한다")
        void currentColumnsMethodReturnsColumns() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build(),
                    ColumnModel.builder().columnName("code").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("items", columns);

            assertEquals(columns, contributor.currentColumns());
            assertEquals(2, contributor.currentColumns().size());
        }

        @Test
        @DisplayName("동일한 값으로 생성된 인스턴스는 equals()에서 true")
        void equalsWithSameValues() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor1 = new PrimaryKeyDropContributor("users", columns);
            PrimaryKeyDropContributor contributor2 = new PrimaryKeyDropContributor("users", columns);

            assertEquals(contributor1, contributor2);
        }

        @Test
        @DisplayName("다른 값으로 생성된 인스턴스는 equals()에서 false")
        void notEqualsWithDifferentValues() {
            Collection<ColumnModel> columns1 = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            Collection<ColumnModel> columns2 = List.of(
                    ColumnModel.builder().columnName("user_id").build()
            );
            PrimaryKeyDropContributor contributor1 = new PrimaryKeyDropContributor("users", columns1);
            PrimaryKeyDropContributor contributor2 = new PrimaryKeyDropContributor("orders", columns2);

            assertNotEquals(contributor1, contributor2);
        }

        @Test
        @DisplayName("hashCode는 동일한 값에 대해 동일하다")
        void hashCodeConsistency() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor1 = new PrimaryKeyDropContributor("users", columns);
            PrimaryKeyDropContributor contributor2 = new PrimaryKeyDropContributor("users", columns);

            assertEquals(contributor1.hashCode(), contributor2.hashCode());
        }

        @Test
        @DisplayName("toString은 유의미한 정보를 포함한다")
        void toStringContainsMeaningfulInfo() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            String toString = contributor.toString();
            assertTrue(toString.contains("users"));
            assertTrue(toString.contains("PrimaryKeyDropContributor"));
        }
    }

    @Nested
    @DisplayName("다양한 테이블 시나리오")
    class TableScenariosTests {

        @Test
        @DisplayName("단일 컬럼 PK 삭제")
        void singleColumnPrimaryKey() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP PRIMARY KEY", stringBuilder.toString());
        }

        @Test
        @DisplayName("복합 PK 삭제")
        void compositeKeyPrimaryKey() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("user_id").build(),
                    ColumnModel.builder().columnName("role_id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("user_roles", columns);

            when(mockDialect.getDropPrimaryKeySql("user_roles", columns))
                    .thenReturn("ALTER TABLE user_roles DROP CONSTRAINT pk_user_roles");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE user_roles DROP CONSTRAINT pk_user_roles", stringBuilder.toString());
        }

        @Test
        @DisplayName("스키마가 포함된 테이블명")
        void tableWithSchema() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("public.users", columns);

            when(mockDialect.getDropPrimaryKeySql("public.users", columns))
                    .thenReturn("ALTER TABLE public.users DROP CONSTRAINT pk_users");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE public.users DROP CONSTRAINT pk_users", stringBuilder.toString());
        }

        @Test
        @DisplayName("특수 문자가 포함된 테이블명")
        void tableWithSpecialCharacters() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("user$accounts", columns);

            when(mockDialect.getDropPrimaryKeySql("user$accounts", columns))
                    .thenReturn("ALTER TABLE \"user$accounts\" DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE \"user$accounts\" DROP PRIMARY KEY", stringBuilder.toString());
        }
    }

    @Nested
    @DisplayName("컬럼 컬렉션 시나리오")
    class ColumnCollectionScenariosTests {

        @Test
        @DisplayName("빈 컬렉션으로 생성 가능")
        void emptyColumnCollection() {
            Collection<ColumnModel> emptyColumns = Collections.emptyList();
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", emptyColumns);

            when(mockDialect.getDropPrimaryKeySql("users", emptyColumns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP PRIMARY KEY", stringBuilder.toString());
        }

        @Test
        @DisplayName("많은 컬럼으로 구성된 복합 PK")
        void manyColumnsCompositeKey() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("tenant_id").build(),
                    ColumnModel.builder().columnName("year").build(),
                    ColumnModel.builder().columnName("month").build(),
                    ColumnModel.builder().columnName("day").build(),
                    ColumnModel.builder().columnName("sequence").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("events", columns);

            when(mockDialect.getDropPrimaryKeySql("events", columns))
                    .thenReturn("ALTER TABLE events DROP CONSTRAINT pk_events");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE events DROP CONSTRAINT pk_events", stringBuilder.toString());
        }

        @Test
        @DisplayName("가변 컬렉션 사용")
        void mutableColumnCollection() {
            Collection<ColumnModel> columns = new ArrayList<>();
            columns.add(ColumnModel.builder().columnName("id").build());

            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP PRIMARY KEY", stringBuilder.toString());
        }
    }

    @Nested
    @DisplayName("StringBuilder 동작")
    class StringBuilderBehaviorTests {

        @Test
        @DisplayName("기존 내용이 있는 StringBuilder에 추가")
        void appendToExistingStringBuilder() {
            stringBuilder.append("-- Previous SQL\n");

            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            String expected = "-- Previous SQL\nALTER TABLE users DROP PRIMARY KEY";
            assertEquals(expected, stringBuilder.toString());
        }

        @Test
        @DisplayName("빈 StringBuilder에 추가")
        void appendToEmptyStringBuilder() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP PRIMARY KEY", stringBuilder.toString());
        }

        @Test
        @DisplayName("여러 번 contribute 호출")
        void multipleContributeCalls() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("DROP PK;");

            contributor.contribute(stringBuilder, mockDialect);
            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("DROP PK;DROP PK;", stringBuilder.toString());
            verify(mockDialect, times(2)).getDropPrimaryKeySql("users", columns);
        }
    }

    @Nested
    @DisplayName("Dialect 통합")
    class DialectIntegrationTests {

        @Test
        @DisplayName("MySQL 스타일 PK 삭제")
        void mysqlStyleDrop() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP PRIMARY KEY", stringBuilder.toString());
        }

        @Test
        @DisplayName("PostgreSQL 스타일 PK 삭제")
        void postgresqlStyleDrop() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP CONSTRAINT users_pkey");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP CONSTRAINT users_pkey", stringBuilder.toString());
        }

        @Test
        @DisplayName("Oracle 스타일 PK 삭제")
        void oracleStyleDrop() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("USERS", columns);

            when(mockDialect.getDropPrimaryKeySql("USERS", columns))
                    .thenReturn("ALTER TABLE USERS DROP CONSTRAINT PK_USERS");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE USERS DROP CONSTRAINT PK_USERS", stringBuilder.toString());
        }

        @Test
        @DisplayName("SQL Server 스타일 PK 삭제")
        void sqlServerStyleDrop() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP CONSTRAINT PK_users");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP CONSTRAINT PK_users", stringBuilder.toString());
        }
    }

    @Nested
    @DisplayName("우선순위 검증")
    class PriorityTests {

        @Test
        @DisplayName("PK 삭제는 우선순위 10을 가진다")
        void primaryKeyDropHasPriority10() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            // PK 삭제는 다른 제약조건보다 먼저 실행되어야 함
            assertEquals(10, contributor.priority());
        }

        @Test
        @DisplayName("여러 contributor의 우선순위 비교")
        void compareWithOtherContributors() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor pkContributor = new PrimaryKeyDropContributor("users", columns);

            // PK 삭제는 우선순위 10
            // (다른 제약조건 삭제는 보통 우선순위 30)
            // PK가 먼저 삭제되어야 함
            assertTrue(pkContributor.priority() < 30);
        }
    }

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCaseTests {

        @Test
        @DisplayName("null이 아닌 빈 문자열 SQL 반환")
        void emptyStringSqlReturn() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns)).thenReturn("");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("", stringBuilder.toString());
        }

        @Test
        @DisplayName("세미콜론을 포함한 SQL 반환")
        void sqlWithSemicolon() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("ALTER TABLE users DROP PRIMARY KEY;");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("ALTER TABLE users DROP PRIMARY KEY;", stringBuilder.toString());
        }

        @Test
        @DisplayName("여러 줄의 SQL 반환")
        void multiLineSql() {
            Collection<ColumnModel> columns = List.of(
                    ColumnModel.builder().columnName("id").build()
            );
            PrimaryKeyDropContributor contributor = new PrimaryKeyDropContributor("users", columns);

            when(mockDialect.getDropPrimaryKeySql("users", columns))
                    .thenReturn("-- Drop primary key\nALTER TABLE users\n  DROP PRIMARY KEY");

            contributor.contribute(stringBuilder, mockDialect);

            assertEquals("-- Drop primary key\nALTER TABLE users\n  DROP PRIMARY KEY",
                    stringBuilder.toString());
        }
    }
}
