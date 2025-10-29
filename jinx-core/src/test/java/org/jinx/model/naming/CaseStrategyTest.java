package org.jinx.model.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CaseStrategyTest {

    @Test
    @DisplayName("LOWER: CaseNormalizer.lower()와 동일 동작")
    void lower_strategy_equivalence() {
        String[] inputs = { null, "", "   ", "  FooBar  ", "MiXeD", "İIıi", "straße" };
        for (String in : inputs) {
            String expected = CaseNormalizer.lower().normalize(in);
            assertThat(CaseStrategy.LOWER.normalize(in)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("UPPER: CaseNormalizer.upper()와 동일 동작")
    void upper_strategy_equivalence() {
        String[] inputs = { null, "", "   ", "  FooBar  ", "MiXeD", "İIıi", "straße" };
        for (String in : inputs) {
            String expected = CaseNormalizer.upper().normalize(in);
            assertThat(CaseStrategy.UPPER.normalize(in)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("PRESERVE: CaseNormalizer.preserve()와 동일 동작")
    void preserve_strategy_equivalence() {
        String[] inputs = { null, "", "   ", "  FooBar  ", "MiXeD", "İIıi", "straße" };
        for (String in : inputs) {
            String expected = CaseNormalizer.preserve().normalize(in);
            assertThat(CaseStrategy.PRESERVE.normalize(in)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("트리밍/로케일: 전략별 변환 결과가 Locale.ROOT 규칙과 일치")
    void trimming_and_locale_root() {
        String s = "  İFoß  ";
        assertThat(CaseStrategy.LOWER.normalize(s))
                .isEqualTo(s.trim().toLowerCase(Locale.ROOT));
        assertThat(CaseStrategy.UPPER.normalize(s))
                .isEqualTo(s.trim().toUpperCase(Locale.ROOT));
        assertThat(CaseStrategy.PRESERVE.normalize(s))
                .isEqualTo(s.trim());
    }

    @Test
    @DisplayName("Idempotence: 전략 재적용 시 결과 불변")
    void idempotence() {
        String s = "  FoO  ";

        String l1 = CaseStrategy.LOWER.normalize(s);
        String l2 = CaseStrategy.LOWER.normalize(l1);
        assertThat(l2).isEqualTo(l1);

        String u1 = CaseStrategy.UPPER.normalize(s);
        String u2 = CaseStrategy.UPPER.normalize(u1);
        assertThat(u2).isEqualTo(u1);

        String p1 = CaseStrategy.PRESERVE.normalize(s);
        String p2 = CaseStrategy.PRESERVE.normalize(p1);
        assertThat(p2).isEqualTo(p1);
    }

    @Test
    @DisplayName("Enum values(): 모든 전략 열거")
    void enum_values() {
        CaseStrategy[] strategies = CaseStrategy.values();

        assertThat(strategies).hasSize(3);
        assertThat(strategies).containsExactly(
                CaseStrategy.LOWER,
                CaseStrategy.UPPER,
                CaseStrategy.PRESERVE
        );
    }

    @Test
    @DisplayName("Enum valueOf(): 이름으로 전략 조회")
    void enum_valueOf() {
        assertThat(CaseStrategy.valueOf("LOWER")).isEqualTo(CaseStrategy.LOWER);
        assertThat(CaseStrategy.valueOf("UPPER")).isEqualTo(CaseStrategy.UPPER);
        assertThat(CaseStrategy.valueOf("PRESERVE")).isEqualTo(CaseStrategy.PRESERVE);
    }

    @Test
    @DisplayName("Enum valueOf(): 잘못된 이름은 예외 발생")
    void enum_valueOf_invalid() {
        try {
            CaseStrategy.valueOf("INVALID");
            assertThat(false).isTrue(); // Should not reach here
        } catch (IllegalArgumentException e) {
            assertThat(e).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Enum name(): 전략 이름 반환")
    void enum_name() {
        assertThat(CaseStrategy.LOWER.name()).isEqualTo("LOWER");
        assertThat(CaseStrategy.UPPER.name()).isEqualTo("UPPER");
        assertThat(CaseStrategy.PRESERVE.name()).isEqualTo("PRESERVE");
    }

    @Test
    @DisplayName("CaseNormalizer 인터페이스 구현 확인")
    void implements_caseNormalizer() {
        // CaseStrategy는 CaseNormalizer를 구현함
        CaseNormalizer lowerNormalizer = CaseStrategy.LOWER;
        CaseNormalizer upperNormalizer = CaseStrategy.UPPER;
        CaseNormalizer preserveNormalizer = CaseStrategy.PRESERVE;

        assertThat(lowerNormalizer.normalize("Test")).isEqualTo("test");
        assertThat(upperNormalizer.normalize("Test")).isEqualTo("TEST");
        assertThat(preserveNormalizer.normalize("Test")).isEqualTo("Test");
    }

    @Test
    @DisplayName("Switch문에서 사용 가능")
    void switch_statement() {
        String input = "  TestCase  ";

        for (CaseStrategy strategy : CaseStrategy.values()) {
            String result = switch (strategy) {
                case LOWER -> strategy.normalize(input);
                case UPPER -> strategy.normalize(input);
                case PRESERVE -> strategy.normalize(input);
            };

            switch (strategy) {
                case LOWER -> assertThat(result).isEqualTo("testcase");
                case UPPER -> assertThat(result).isEqualTo("TESTCASE");
                case PRESERVE -> assertThat(result).isEqualTo("TestCase");
            }
        }
    }

    @Test
    @DisplayName("Enum 순서는 선언 순서와 동일")
    void enum_ordinal() {
        assertThat(CaseStrategy.LOWER.ordinal()).isEqualTo(0);
        assertThat(CaseStrategy.UPPER.ordinal()).isEqualTo(1);
        assertThat(CaseStrategy.PRESERVE.ordinal()).isEqualTo(2);
    }

    @Test
    @DisplayName("SQL 식별자 패턴 처리")
    void sql_identifier_patterns() {
        // snake_case
        assertThat(CaseStrategy.LOWER.normalize("user_accounts")).isEqualTo("user_accounts");
        assertThat(CaseStrategy.UPPER.normalize("user_accounts")).isEqualTo("USER_ACCOUNTS");
        assertThat(CaseStrategy.PRESERVE.normalize("user_accounts")).isEqualTo("user_accounts");

        // camelCase
        assertThat(CaseStrategy.LOWER.normalize("userAccounts")).isEqualTo("useraccounts");
        assertThat(CaseStrategy.UPPER.normalize("userAccounts")).isEqualTo("USERACCOUNTS");
        assertThat(CaseStrategy.PRESERVE.normalize("userAccounts")).isEqualTo("userAccounts");

        // PascalCase
        assertThat(CaseStrategy.LOWER.normalize("UserAccounts")).isEqualTo("useraccounts");
        assertThat(CaseStrategy.UPPER.normalize("UserAccounts")).isEqualTo("USERACCOUNTS");
        assertThat(CaseStrategy.PRESERVE.normalize("UserAccounts")).isEqualTo("UserAccounts");

        // SCREAMING_SNAKE_CASE
        assertThat(CaseStrategy.LOWER.normalize("USER_ACCOUNTS")).isEqualTo("user_accounts");
        assertThat(CaseStrategy.UPPER.normalize("USER_ACCOUNTS")).isEqualTo("USER_ACCOUNTS");
        assertThat(CaseStrategy.PRESERVE.normalize("USER_ACCOUNTS")).isEqualTo("USER_ACCOUNTS");
    }

    @Test
    @DisplayName("데이터베이스별 명명 규칙 시뮬레이션")
    void database_naming_conventions() {
        String tableName = "UserAccounts";

        // PostgreSQL: 소문자 (quoted identifier가 아닐 때)
        assertThat(CaseStrategy.LOWER.normalize(tableName)).isEqualTo("useraccounts");

        // Oracle: 대문자 (quoted identifier가 아닐 때)
        assertThat(CaseStrategy.UPPER.normalize(tableName)).isEqualTo("USERACCOUNTS");

        // MySQL: 대소문자 보존 (Windows에서는 case-insensitive, Unix에서는 sensitive)
        assertThat(CaseStrategy.PRESERVE.normalize(tableName)).isEqualTo("UserAccounts");
    }

    @Test
    @DisplayName("특수 문자 포함 식별자")
    void special_characters_in_identifiers() {
        String identifier = "user$account_123";

        assertThat(CaseStrategy.LOWER.normalize(identifier)).isEqualTo("user$account_123");
        assertThat(CaseStrategy.UPPER.normalize(identifier)).isEqualTo("USER$ACCOUNT_123");
        assertThat(CaseStrategy.PRESERVE.normalize(identifier)).isEqualTo("user$account_123");
    }

    @Test
    @DisplayName("매우 긴 식별자 처리")
    void very_long_identifier() {
        // Oracle: 최대 128자, PostgreSQL: 최대 63자
        String longIdentifier = "A".repeat(100) + "_" + "b".repeat(100);

        String lowerResult = CaseStrategy.LOWER.normalize(longIdentifier);
        String upperResult = CaseStrategy.UPPER.normalize(longIdentifier);
        String preserveResult = CaseStrategy.PRESERVE.normalize(longIdentifier);

        assertThat(lowerResult).hasSize(201);
        assertThat(upperResult).hasSize(201);
        assertThat(preserveResult).hasSize(201);

        assertThat(lowerResult).startsWith("a".repeat(100));
        assertThat(upperResult).startsWith("A".repeat(100));
        assertThat(preserveResult).startsWith("A".repeat(100));
    }

    @Test
    @DisplayName("Enum equality: 같은 인스턴스 보장")
    void enum_singleton() {
        // Enum은 싱글톤이므로 == 비교 가능
        assertThat(CaseStrategy.LOWER == CaseStrategy.valueOf("LOWER")).isTrue();
        assertThat(CaseStrategy.UPPER == CaseStrategy.valueOf("UPPER")).isTrue();
        assertThat(CaseStrategy.PRESERVE == CaseStrategy.valueOf("PRESERVE")).isTrue();

        // equals()도 동작
        assertThat(CaseStrategy.LOWER).isEqualTo(CaseStrategy.valueOf("LOWER"));
        assertThat(CaseStrategy.UPPER).isEqualTo(CaseStrategy.valueOf("UPPER"));
        assertThat(CaseStrategy.PRESERVE).isEqualTo(CaseStrategy.valueOf("PRESERVE"));
    }

    @Test
    @DisplayName("Enum compareTo(): 순서 비교")
    void enum_compareTo() {
        assertThat(CaseStrategy.LOWER.compareTo(CaseStrategy.UPPER)).isLessThan(0);
        assertThat(CaseStrategy.UPPER.compareTo(CaseStrategy.PRESERVE)).isLessThan(0);
        assertThat(CaseStrategy.PRESERVE.compareTo(CaseStrategy.LOWER)).isGreaterThan(0);
        assertThat(CaseStrategy.LOWER.compareTo(CaseStrategy.LOWER)).isEqualTo(0);
    }

    @Test
    @DisplayName("실제 사용 시나리오: 테이블명 정규화")
    void real_world_scenario_table_normalization() {
        // JPA 엔티티명 -> DB 테이블명 변환 시뮬레이션
        String[] entityNames = {
                "User", "UserAccount", "OrderDetail", "ProductCategory"
        };

        for (String entityName : entityNames) {
            // PostgreSQL 스타일
            String pgTable = CaseStrategy.LOWER.normalize(entityName);
            assertThat(pgTable).isLowerCase();

            // Oracle 스타일
            String oracleTable = CaseStrategy.UPPER.normalize(entityName);
            assertThat(oracleTable).isUpperCase();

            // MySQL 스타일 (원본 보존)
            String mysqlTable = CaseStrategy.PRESERVE.normalize(entityName);
            assertThat(mysqlTable).isEqualTo(entityName);
        }
    }

    @Test
    @DisplayName("null 안전성: 모든 전략은 null을 빈 문자열로 변환")
    void null_safety() {
        assertThat(CaseStrategy.LOWER.normalize(null)).isEqualTo("");
        assertThat(CaseStrategy.UPPER.normalize(null)).isEqualTo("");
        assertThat(CaseStrategy.PRESERVE.normalize(null)).isEqualTo("");

        // 체이닝 시에도 안전
        String result = CaseStrategy.LOWER.normalize(
                CaseStrategy.UPPER.normalize(null)
        );
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("공백 처리 일관성")
    void whitespace_handling_consistency() {
        String[] whitespaces = {"", "   ", "\t", "\n", "\r", " \t\n\r "};

        for (String ws : whitespaces) {
            assertThat(CaseStrategy.LOWER.normalize(ws)).isEqualTo("");
            assertThat(CaseStrategy.UPPER.normalize(ws)).isEqualTo("");
            assertThat(CaseStrategy.PRESERVE.normalize(ws)).isEqualTo("");
        }
    }

    @Test
    @DisplayName("전략 간 결과 차이 검증")
    void strategy_differences() {
        String mixedCase = "MixedCase";

        String lower = CaseStrategy.LOWER.normalize(mixedCase);
        String upper = CaseStrategy.UPPER.normalize(mixedCase);
        String preserve = CaseStrategy.PRESERVE.normalize(mixedCase);

        // 모두 다른 결과
        assertThat(lower).isNotEqualTo(upper);
        assertThat(upper).isNotEqualTo(preserve);
        assertThat(lower).isNotEqualTo(preserve);

        // 예상 결과
        assertThat(lower).isEqualTo("mixedcase");
        assertThat(upper).isEqualTo("MIXEDCASE");
        assertThat(preserve).isEqualTo("MixedCase");
    }
}
