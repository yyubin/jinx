package org.jinx.migration.dialect.mysql;

import org.jinx.migration.spi.JavaTypeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySqlValueTransformerTest {

    private final MySqlValueTransformer vt = new MySqlValueTransformer();
    private final JavaTypeMapper.JavaType dummyType = mock(JavaTypeMapper.JavaType.class);

    @Nested @DisplayName("quote()")
    class QuoteMethod {

        @Test @DisplayName("null 입력 → \"NULL\" 문자열 반환")
        void nullInput_returnsNULLLiteral() {
            String quoted = vt.quote(null, dummyType);
            assertThat(quoted).isEqualTo("NULL");
        }

        @Test @DisplayName("단순 문자열은 양쪽에 작은따옴표 추가")
        void wrapsWithSingleQuotes() {
            String quoted = vt.quote("hello", dummyType);
            assertThat(quoted).isEqualTo("'hello'");
        }

        @Test @DisplayName("문자열 내부의 작은따옴표는 두 번으로 Escape")
        void escapesSingleQuotesInsideString() {
            String input  = "O'Brien";
            String quoted = vt.quote(input, dummyType);
            assertThat(quoted).isEqualTo("'O''Brien'");
        }

        @Test @DisplayName("숫자 문자열도 그대로 따옴표로 감싼다 (Transformer 논리 확인)")
        void numericStringIsStillQuoted() {
            String quoted = vt.quote("12345", dummyType);
            assertThat(quoted).isEqualTo("'12345'");
        }
    }
}
