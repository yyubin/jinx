package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.JavaTypeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSqlValueTransformerTest {

    private final PostgreSqlValueTransformer vt = new PostgreSqlValueTransformer();
    private final JavaTypeMapper.JavaType anyType = mock(JavaTypeMapper.JavaType.class);

    @Test @DisplayName("null 값은 NULL 반환")
    void nullValue_returnsNULL() {
        assertEquals("NULL", vt.quote(null, anyType));
    }

    @Test @DisplayName("일반 문자열은 단따옴표로 감싼다")
    void normalString_wrapsInSingleQuotes() {
        assertEquals("'hello'", vt.quote("hello", anyType));
    }

    @Test @DisplayName("단따옴표는 두 개로 이스케이프")
    void singleQuote_isDoubled() {
        assertEquals("'it''s'", vt.quote("it's", anyType));
        // 단따옴표 하나 → 외부 따옴표로 감싸 + 내부 이스케이프 → ''''
        assertEquals("''''", vt.quote("'", anyType));
    }

    @Test @DisplayName("백슬래시는 standard_conforming_strings 기본값에서 이스케이프 불필요")
    void backslash_notEscaped() {
        assertEquals("'C:\\path'", vt.quote("C:\\path", anyType));
    }

    @Test @DisplayName("빈 문자열 처리")
    void emptyString() {
        assertEquals("''", vt.quote("", anyType));
    }
}
