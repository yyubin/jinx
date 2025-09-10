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
}
