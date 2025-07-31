package org.jinx.migration.dialect.mysql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MySqlUtilTest {

    @Nested @DisplayName("isKeyword()")
    class IsKeyword {

        @Test @DisplayName("예약어를 대소문자 무관하게 인식한다")
        void returnsTrueForReservedWord_caseInsensitive() {
            assertThat(MySqlUtil.isKeyword("select")).isTrue();
            assertThat(MySqlUtil.isKeyword("SELECT")).isTrue();
            assertThat(MySqlUtil.isKeyword("SeLeCt")).isTrue();
        }

        @Test @DisplayName("예약어가 아니면 false")
        void returnsFalseForNonKeyword() {
            assertThat(MySqlUtil.isKeyword("myTable")).isFalse();
            assertThat(MySqlUtil.isKeyword("foo_bar")).isFalse();
        }
    }

    @Nested @DisplayName("escapeKeyword()")
    class EscapeKeyword {

        @Test @DisplayName("예약어면 뒤에 _ 를 붙여 준다")
        void appendsUnderscoreForKeyword() {
            assertThat(MySqlUtil.escapeKeyword("order")).isEqualTo("order_");
            assertThat(MySqlUtil.escapeKeyword("ORDER")).isEqualTo("ORDER_");
        }

        @Test @DisplayName("예약어가 아니면 원본을 그대로 반환")
        void returnsOriginalForNonKeyword() {
            assertThat(MySqlUtil.escapeKeyword("employee")).isEqualTo("employee");
        }

        @Test @DisplayName("null 입력은 null 반환")
        void nullInputReturnsNull() {
            assertThat(MySqlUtil.escapeKeyword(null)).isNull();
        }
    }
}
