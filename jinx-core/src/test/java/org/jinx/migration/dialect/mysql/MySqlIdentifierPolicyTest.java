package org.jinx.migration.dialect.mysql;

import org.jinx.migration.spi.IdentifierPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MySqlIdentifierPolicyTest {

    private final IdentifierPolicy policy = new MySqlIdentifierPolicy();

    @Test
    @DisplayName("최대 길이는 64")
    void maxLength_is64() {
        assertEquals(64, policy.maxLength());
    }

    @Test
    @DisplayName("quote는 backtick(`)으로 감싼다")
    void quote_wrapsWithBacktick() {
        assertEquals("`users`", policy.quote("users"));
        assertEquals("`Order`", policy.quote("Order"));
    }

    @Test
    @DisplayName("normalizeCase는 입력을 그대로 반환한다")
    void normalizeCase_returnsRaw() {
        assertEquals("AbCdEf", policy.normalizeCase("AbCdEf"));
    }

    @Test
    @DisplayName("isKeyword는 MySQL 예약어를 식별한다")
    void isKeyword_detectsReserved() {
        assertTrue(policy.isKeyword("SELECT"));   // 예약어
        assertTrue(policy.isKeyword("table"));    // 대소문자 무관
        assertFalse(policy.isKeyword("notAKeyword"));
    }
}
