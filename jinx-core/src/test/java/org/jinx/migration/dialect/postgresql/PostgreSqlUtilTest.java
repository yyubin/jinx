package org.jinx.migration.dialect.postgresql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlUtilTest {

    @Nested
    @DisplayName("isKeyword")
    class IsKeyword {

        @Test @DisplayName("SELECT는 예약어")
        void select_isKeyword() {
            assertTrue(PostgreSqlUtil.isKeyword("SELECT"));
        }

        @Test @DisplayName("소문자도 예약어로 인식")
        void lowercase_isKeyword() {
            assertTrue(PostgreSqlUtil.isKeyword("select"));
            assertTrue(PostgreSqlUtil.isKeyword("table"));
            assertTrue(PostgreSqlUtil.isKeyword("index"));
        }

        @Test @DisplayName("일반 식별자는 예약어 아님")
        void normalName_notKeyword() {
            assertFalse(PostgreSqlUtil.isKeyword("user_name"));
            assertFalse(PostgreSqlUtil.isKeyword("order_id"));
            assertFalse(PostgreSqlUtil.isKeyword("my_column"));
        }

        @Test @DisplayName("PG 고유 예약어 확인")
        void pgSpecific_keywords() {
            assertTrue(PostgreSqlUtil.isKeyword("ILIKE"));
            assertTrue(PostgreSqlUtil.isKeyword("LATERAL"));
            assertTrue(PostgreSqlUtil.isKeyword("VARIADIC"));
        }
    }

    @Nested
    @DisplayName("escapeKeyword")
    class EscapeKeyword {

        @Test @DisplayName("예약어에 _ 접미사 추가")
        void escapesReservedWord() {
            assertEquals("select_", PostgreSqlUtil.escapeKeyword("select"));
            assertEquals("TABLE_", PostgreSqlUtil.escapeKeyword("TABLE"));
        }

        @Test @DisplayName("일반 이름은 그대로 반환")
        void normalNameUnchanged() {
            assertEquals("my_col", PostgreSqlUtil.escapeKeyword("my_col"));
        }

        @Test @DisplayName("null 입력 시 null 반환")
        void nullInput_returnsNull() {
            assertNull(PostgreSqlUtil.escapeKeyword(null));
        }
    }

    @Nested
    @DisplayName("defaultPkConstraintName")
    class DefaultPkConstraintName {

        @Test @DisplayName("테이블명 + _pkey 반환")
        void returnsTableNamePkey() {
            assertEquals("users_pkey", PostgreSqlUtil.defaultPkConstraintName("users"));
            assertEquals("order_item_pkey", PostgreSqlUtil.defaultPkConstraintName("order_item"));
        }
    }
}
