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
}
