package org.jinx.model.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CaseNormalizerTest {

    @Test
    @DisplayName("lower(): null -> 빈문자열, 트리밍 후 소문자(Locale.ROOT)")
    void lower_basic() {
        CaseNormalizer lower = CaseNormalizer.lower();

        assertThat(lower.normalize(null)).isEqualTo("");
        assertThat(lower.normalize("")).isEqualTo("");
        assertThat(lower.normalize("   ")).isEqualTo("");
        assertThat(lower.normalize("  FooBar  ")).isEqualTo("foobar");
        assertThat(lower.normalize("MiXeD")).isEqualTo("mixed");
    }

    @Test
    @DisplayName("upper(): null -> 빈문자열, 트리밍 후 대문자(Locale.ROOT)")
    void upper_basic() {
        CaseNormalizer upper = CaseNormalizer.upper();

        assertThat(upper.normalize(null)).isEqualTo("");
        assertThat(upper.normalize("")).isEqualTo("");
        assertThat(upper.normalize("   ")).isEqualTo("");
        assertThat(upper.normalize("  FooBar  ")).isEqualTo("FOOBAR");
        assertThat(upper.normalize("MiXeD")).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("preserve(): null -> 빈문자열, 트리밍만 수행(대소문자 보존)")
    void preserve_basic() {
        CaseNormalizer preserve = CaseNormalizer.preserve();

        assertThat(preserve.normalize(null)).isEqualTo("");
        assertThat(preserve.normalize("")).isEqualTo("");
        assertThat(preserve.normalize("   ")).isEqualTo("");
        assertThat(preserve.normalize("  FooBar  ")).isEqualTo("FooBar");
        assertThat(preserve.normalize("MiXeD")).isEqualTo("MiXeD");
    }

    @Test
    @DisplayName("Locale.ROOT 케이스: 비-ASCII 문자의 처리 일관성 검증(터키어 I, 독일어 ß 등)")
    void locale_root_consistency() {
        String turkish = "İIıi"; // 대문자 I+점, 대문자 I, 소문자 ı(점 없음), 소문자 i
        String germanSharpS = "straße"; // ß 포함

        // lower/upper는 Locale.ROOT를 사용하므로, 자바의 표준 변환 결과와 동일해야 한다
        assertThat(CaseNormalizer.lower().normalize(turkish))
                .isEqualTo(turkish.trim().toLowerCase(Locale.ROOT));
        assertThat(CaseNormalizer.upper().normalize(turkish))
                .isEqualTo(turkish.trim().toUpperCase(Locale.ROOT));

        assertThat(CaseNormalizer.lower().normalize(germanSharpS))
                .isEqualTo(germanSharpS.trim().toLowerCase(Locale.ROOT));
        assertThat(CaseNormalizer.upper().normalize(germanSharpS))
                .isEqualTo(germanSharpS.trim().toUpperCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Idempotence: 같은 전략을 여러 번 적용해도 결과 불변")
    void idempotence() {
        String s = "  FoO  ";
        String l1 = CaseNormalizer.lower().normalize(s);
        String l2 = CaseNormalizer.lower().normalize(l1);
        assertThat(l2).isEqualTo(l1);

        String u1 = CaseNormalizer.upper().normalize(s);
        String u2 = CaseNormalizer.upper().normalize(u1);
        assertThat(u2).isEqualTo(u1);

        String p1 = CaseNormalizer.preserve().normalize(s);
        String p2 = CaseNormalizer.preserve().normalize(p1);
        assertThat(p2).isEqualTo(p1);
    }

    @Test
    @DisplayName("특수 문자 처리: 언더스코어, 하이픈, 숫자는 보존")
    void specialCharacters() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        String input = "User_Name-123";
        assertThat(lower.normalize(input)).isEqualTo("user_name-123");
        assertThat(upper.normalize(input)).isEqualTo("USER_NAME-123");
        assertThat(preserve.normalize(input)).isEqualTo("User_Name-123");
    }

    @Test
    @DisplayName("숫자만 포함된 문자열 처리")
    void numbersOnly() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        String numbers = "  12345  ";
        assertThat(lower.normalize(numbers)).isEqualTo("12345");
        assertThat(upper.normalize(numbers)).isEqualTo("12345");
        assertThat(preserve.normalize(numbers)).isEqualTo("12345");
    }

    @Test
    @DisplayName("다양한 공백 문자 처리: 탭, 줄바꿈, 캐리지 리턴")
    void variousWhitespace() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        // 탭과 공백
        assertThat(lower.normalize("\t\tFoo\t\t")).isEqualTo("foo");
        assertThat(upper.normalize("\t\tFoo\t\t")).isEqualTo("FOO");
        assertThat(preserve.normalize("\t\tFoo\t\t")).isEqualTo("Foo");

        // 줄바꿈
        assertThat(lower.normalize("\nBar\n")).isEqualTo("bar");
        assertThat(upper.normalize("\nBar\n")).isEqualTo("BAR");
        assertThat(preserve.normalize("\nBar\n")).isEqualTo("Bar");

        // 캐리지 리턴
        assertThat(lower.normalize("\rBaz\r")).isEqualTo("baz");
        assertThat(upper.normalize("\rBaz\r")).isEqualTo("BAZ");
        assertThat(preserve.normalize("\rBaz\r")).isEqualTo("Baz");
    }

    @Test
    @DisplayName("연속된 공백 처리: 중간 공백은 유지")
    void multipleSpacesInMiddle() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        String input = "  First   Second  ";
        // trim()은 앞뒤만 제거, 중간 공백은 유지
        assertThat(lower.normalize(input)).isEqualTo("first   second");
        assertThat(upper.normalize(input)).isEqualTo("FIRST   SECOND");
        assertThat(preserve.normalize(input)).isEqualTo("First   Second");
    }

    @Test
    @DisplayName("유니코드 문자 처리: 이모지, CJK 문자")
    void unicodeCharacters() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        // 이모지는 변환되지 않음
        String emoji = "  User😀  ";
        assertThat(lower.normalize(emoji)).isEqualTo("user😀");
        assertThat(upper.normalize(emoji)).isEqualTo("USER😀");
        assertThat(preserve.normalize(emoji)).isEqualTo("User😀");

        // 한글은 변환되지 않음
        String korean = "  사용자Name  ";
        assertThat(lower.normalize(korean)).isEqualTo("사용자name");
        assertThat(upper.normalize(korean)).isEqualTo("사용자NAME");
        assertThat(preserve.normalize(korean)).isEqualTo("사용자Name");

        // 한자는 변환되지 않음
        String chinese = "  用户Name  ";
        assertThat(lower.normalize(chinese)).isEqualTo("用户name");
        assertThat(upper.normalize(chinese)).isEqualTo("用户NAME");
        assertThat(preserve.normalize(chinese)).isEqualTo("用户Name");
    }

    @Test
    @DisplayName("매우 긴 문자열 처리")
    void longString() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();

        String longInput = "A".repeat(1000) + "b".repeat(1000);
        String expectedLower = "a".repeat(1000) + "b".repeat(1000);
        String expectedUpper = "A".repeat(1000) + "B".repeat(1000);

        assertThat(lower.normalize(longInput)).isEqualTo(expectedLower);
        assertThat(upper.normalize(longInput)).isEqualTo(expectedUpper);
    }

    @Test
    @DisplayName("SQL 식별자에서 흔히 사용되는 패턴")
    void commonSqlIdentifierPatterns() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        // 테이블명 패턴
        assertThat(lower.normalize("user_accounts")).isEqualTo("user_accounts");
        assertThat(upper.normalize("user_accounts")).isEqualTo("USER_ACCOUNTS");
        assertThat(preserve.normalize("user_accounts")).isEqualTo("user_accounts");

        // 카멜케이스
        assertThat(lower.normalize("userAccounts")).isEqualTo("useraccounts");
        assertThat(upper.normalize("userAccounts")).isEqualTo("USERACCOUNTS");
        assertThat(preserve.normalize("userAccounts")).isEqualTo("userAccounts");

        // 파스칼케이스
        assertThat(lower.normalize("UserAccounts")).isEqualTo("useraccounts");
        assertThat(upper.normalize("UserAccounts")).isEqualTo("USERACCOUNTS");
        assertThat(preserve.normalize("UserAccounts")).isEqualTo("UserAccounts");
    }

    @Test
    @DisplayName("커스텀 CaseNormalizer 구현")
    void customNormalizer() {
        // 첫 글자만 대문자로 만드는 커스텀 normalizer
        CaseNormalizer titleCase = s -> {
            if (s == null || s.isEmpty()) return "";
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return "";
            return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) +
                   trimmed.substring(1).toLowerCase(Locale.ROOT);
        };

        assertThat(titleCase.normalize("hello WORLD")).isEqualTo("Hello world");
        assertThat(titleCase.normalize("  FOO  ")).isEqualTo("Foo");
        assertThat(titleCase.normalize("a")).isEqualTo("A");
    }

    @Test
    @DisplayName("빈 문자열과 공백만 있는 문자열의 구분")
    void emptyVsBlank() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        // 빈 문자열
        assertThat(lower.normalize("")).isEqualTo("");
        assertThat(upper.normalize("")).isEqualTo("");
        assertThat(preserve.normalize("")).isEqualTo("");

        // 공백만 있는 문자열 (모두 trim 후 빈 문자열)
        assertThat(lower.normalize("   ")).isEqualTo("");
        assertThat(upper.normalize("   ")).isEqualTo("");
        assertThat(preserve.normalize("   ")).isEqualTo("");

        assertThat(lower.normalize("\t\t")).isEqualTo("");
        assertThat(upper.normalize("\t\t")).isEqualTo("");
        assertThat(preserve.normalize("\t\t")).isEqualTo("");
    }

    @Test
    @DisplayName("단일 문자 처리")
    void singleCharacter() {
        CaseNormalizer lower = CaseNormalizer.lower();
        CaseNormalizer upper = CaseNormalizer.upper();
        CaseNormalizer preserve = CaseNormalizer.preserve();

        assertThat(lower.normalize("A")).isEqualTo("a");
        assertThat(upper.normalize("a")).isEqualTo("A");
        assertThat(preserve.normalize("A")).isEqualTo("A");
        assertThat(preserve.normalize("a")).isEqualTo("a");
    }

    @Test
    @DisplayName("normalize() 메서드는 함수형 인터페이스로 사용 가능")
    void functionalInterface() {
        // 함수형 인터페이스로서 람다 표현식으로 사용 가능
        CaseNormalizer customNormalizer = input -> {
            if (input == null) return "NULL";
            return input.trim().toUpperCase(Locale.ROOT);
        };

        assertThat(customNormalizer.normalize(null)).isEqualTo("NULL");
        assertThat(customNormalizer.normalize("  test  ")).isEqualTo("TEST");
    }
}
