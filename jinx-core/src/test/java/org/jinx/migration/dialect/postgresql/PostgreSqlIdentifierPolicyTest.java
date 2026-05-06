package org.jinx.migration.dialect.postgresql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlIdentifierPolicyTest {

    private final PostgreSqlIdentifierPolicy policy = new PostgreSqlIdentifierPolicy();

    @Test @DisplayName("최대 식별자 길이는 63")
    void maxLength_is63() {
        assertEquals(63, policy.maxLength());
    }

    @Test @DisplayName("식별자를 double-quote로 감싼다")
    void quote_usesDoubleQuote() {
        assertEquals("\"users\"", policy.quote("users"));
        assertEquals("\"order\"", policy.quote("order"));
    }

    @Test @DisplayName("normalizeCase는 소문자로 변환")
    void normalizeCase_toLowercase() {
        assertEquals("username", policy.normalizeCase("UserName"));
        assertEquals("my_table", policy.normalizeCase("MY_TABLE"));
    }

    @Test @DisplayName("예약어 isKeyword 위임")
    void isKeyword_delegatesToUtil() {
        assertTrue(policy.isKeyword("SELECT"));
        assertTrue(policy.isKeyword("table"));
        assertFalse(policy.isKeyword("user_id"));
    }
}
