package org.jinx.spi.naming.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnakeCaseNamingStrategyTest {

    private final SnakeCaseNamingStrategy strategy = new SnakeCaseNamingStrategy();

    @DisplayName("컬럼명: 단순 카멜케이스 → 스네이크케이스 변환")
    @ParameterizedTest
    @CsvSource({
        "maxLevel, max_level",
        "userName, user_name",
        "coinPower, coin_power",
        "basePower, base_power",
        "isActive, is_active",
        "isPrimary, is_primary"
    })
    void toPhysicalColumnName_simple_camelCase(String input, String expected) {
        assertEquals(expected, strategy.toPhysicalColumnName(input));
    }

    @DisplayName("컬럼명: 연속된 대문자 처리")
    @ParameterizedTest
    @CsvSource({
        "HTTPServer, http_server",
        "getHTTPResponseCode, get_http_response_code",
        "parseHTMLString, parse_html_string",
        "IOError, io_error"
    })
    void toPhysicalColumnName_consecutive_uppercase(String input, String expected) {
        assertEquals(expected, strategy.toPhysicalColumnName(input));
    }

    @DisplayName("컬럼명: 숫자 포함 케이스")
    @ParameterizedTest
    @CsvSource({
        "get2HTTPResponse, get2_http_response",
        "level1Name, level1_name",
        "base64Encode, base64_encode"
    })
    void toPhysicalColumnName_with_numbers(String input, String expected) {
        assertEquals(expected, strategy.toPhysicalColumnName(input));
    }

    @DisplayName("컬럼명: 이미 스네이크케이스인 경우")
    @Test
    void toPhysicalColumnName_already_snake_case() {
        assertEquals("max_level", strategy.toPhysicalColumnName("max_level"));
        assertEquals("user_name", strategy.toPhysicalColumnName("user_name"));
    }

    @DisplayName("컬럼명: 소문자만 있는 경우")
    @Test
    void toPhysicalColumnName_all_lowercase() {
        assertEquals("name", strategy.toPhysicalColumnName("name"));
        assertEquals("id", strategy.toPhysicalColumnName("id"));
    }

    @DisplayName("컬럼명: 빈 문자열 및 null 처리")
    @Test
    void toPhysicalColumnName_empty_and_null() {
        assertEquals("", strategy.toPhysicalColumnName(""));
        assertNull(strategy.toPhysicalColumnName(null));
    }

    @DisplayName("컬럼명: 첫 문자가 대문자인 경우")
    @ParameterizedTest
    @CsvSource({
        "MaxLevel, max_level",
        "UserName, user_name",
        "ID, id"
    })
    void toPhysicalColumnName_starts_with_uppercase(String input, String expected) {
        assertEquals(expected, strategy.toPhysicalColumnName(input));
    }

    @DisplayName("테이블명: Jpa 접미사 제거")
    @Test
    void toPhysicalTableName_removes_jpa_suffix() {
        assertEquals("persona", strategy.toPhysicalTableName("PersonaJpa"));
        assertEquals("skill", strategy.toPhysicalTableName("SkillJpa"));
        assertEquals("user", strategy.toPhysicalTableName("UserJpa"));
    }

    @DisplayName("테이블명: Entity 접미사 제거")
    @Test
    void toPhysicalTableName_removes_entity_suffix() {
        assertEquals("persona", strategy.toPhysicalTableName("PersonaEntity"));
        assertEquals("skill", strategy.toPhysicalTableName("SkillEntity"));
    }

    @DisplayName("테이블명: 접미사 없는 경우")
    @Test
    void toPhysicalTableName_without_suffix() {
        assertEquals("persona", strategy.toPhysicalTableName("Persona"));
        assertEquals("skill_stats", strategy.toPhysicalTableName("SkillStats"));
    }

    @DisplayName("테이블명: 카멜케이스 변환")
    @Test
    void toPhysicalTableName_camelCase_conversion() {
        assertEquals("skill_stats_by_sync", strategy.toPhysicalTableName("SkillStatsBySyncJpa"));
        assertEquals("persona_image", strategy.toPhysicalTableName("PersonaImageJpa"));
    }

    @DisplayName("제약조건명: UNIQUE 타입")
    @Test
    void toPhysicalConstraintName_unique() {
        String result = strategy.toPhysicalConstraintName(
            "persona",
            "UNIQUE",
            List.of("max_level")
        );
        assertEquals("uq_persona__max_level", result);
    }

    @DisplayName("제약조건명: FOREIGN_KEY 타입")
    @Test
    void toPhysicalConstraintName_foreign_key() {
        String result = strategy.toPhysicalConstraintName(
            "skill",
            "FOREIGN_KEY",
            List.of("persona_id")
        );
        assertEquals("fk_skill__persona_id", result);
    }

    @DisplayName("제약조건명: PRIMARY_KEY 타입")
    @Test
    void toPhysicalConstraintName_primary_key() {
        String result = strategy.toPhysicalConstraintName(
            "persona",
            "PRIMARY_KEY",
            List.of("id")
        );
        assertEquals("pk_persona__id", result);
    }

    @DisplayName("제약조건명: CHECK 타입")
    @Test
    void toPhysicalConstraintName_check() {
        String result = strategy.toPhysicalConstraintName(
            "persona",
            "CHECK",
            List.of("max_level")
        );
        assertEquals("ck_persona__max_level", result);
    }

    @DisplayName("제약조건명: 복합 컬럼")
    @Test
    void toPhysicalConstraintName_multiple_columns() {
        String result = strategy.toPhysicalConstraintName(
            "persona",
            "UNIQUE",
            List.of("sinner_id", "season_number")
        );
        assertEquals("uq_persona__sinner_id_season_number", result);
    }

    @DisplayName("제약조건명: null 타입 처리")
    @Test
    void toPhysicalConstraintName_null_type() {
        String result = strategy.toPhysicalConstraintName(
            "persona",
            null,
            List.of("id")
        );
        assertEquals("ct_persona__id", result);
    }

    @DisplayName("인덱스명: 단일 컬럼")
    @Test
    void toPhysicalIndexName_single_column() {
        String result = strategy.toPhysicalIndexName("persona", List.of("sinner_id"));
        assertEquals("ix_persona__sinner_id", result);
    }

    @DisplayName("인덱스명: 복합 컬럼")
    @Test
    void toPhysicalIndexName_multiple_columns() {
        String result = strategy.toPhysicalIndexName(
            "persona",
            List.of("sinner_id", "season_number")
        );
        assertEquals("ix_persona__sinner_id_season_number", result);
    }
}
