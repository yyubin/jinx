package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jinx.migration.spi.IdentifierPolicy;
import org.jinx.model.naming.CaseNormalizer;

import java.util.Objects;

/**
 * 컬럼 식별자 키. 내부 비교용 정규화된 키와 외부 표시용 원본 키를 분리하여 관리합니다.
 * 
 * - canonical: DB별 대소문자 정규화 규칙을 적용한 비교/Map 키 용도
 * - display: 원본 대소문자를 보존한 표시/로그 용도
 */
public final class ColumnKey {
    private static final String DELIMITER = "::";
    
    private final String canonical;  // 정규화된 키 (DB 비교용)
    private final String display;    // 원본 키 (표시용)
    
    private ColumnKey(String canonical, String display) {
        this.canonical = canonical;
        this.display = display;
    }

    // 기본 정책(소문자 변환)으로 ColumnKey 생성
    public static ColumnKey of(String tableName, String columnName) {
        String normalizedTableName = (tableName == null || tableName.isBlank()) ? "" : tableName.trim();
        String normalizedColumnName = (columnName == null) ? "" : columnName.trim();
        
        String displayKey = normalizedTableName + DELIMITER + normalizedColumnName;
        String canonicalKey = normalizedTableName.toLowerCase(java.util.Locale.ROOT) + DELIMITER + 
                             normalizedColumnName.toLowerCase(java.util.Locale.ROOT);
        
        return new ColumnKey(canonicalKey, displayKey);
    }

    // IdentifierPolicy를 사용하여 ColumnKey 생성
    public static ColumnKey of(String tableName, String columnName, CaseNormalizer normalizer) {
        Objects.requireNonNull(normalizer, "normalizer must not be null");
        String t = tableName == null ? "" : tableName.trim();
        String c = columnName == null ? "" : columnName.trim();

        String displayKey = t + DELIMITER + c;
        String canonicalKey = normalizer.normalize(t) + DELIMITER + normalizer.normalize(c);
        return new ColumnKey(canonicalKey, displayKey);
    }

    // 정규화된 키 반환 (Map 키, 비교 용도)
    public String canonical() {
        return canonical;
    }

    // 표시용 키 반환 (로그, 외부 노출 용도)
    public String display() {
        return display;
    }

    @JsonValue
    public String toJsonValue() {
        return display;
    }

    // 기존 기본 오버로드(하위호환): 단일 토큰 처리만 개선, 기본 lower
    @JsonCreator
    public static ColumnKey fromJsonValue(String value) {
        if (value == null || value.isBlank()) return ColumnKey.of("", "");
        int idx = value.indexOf(DELIMITER);
        if (idx < 0) return ColumnKey.of("", value.trim());
        String table = value.substring(0, idx).trim();
        String column = value.substring(idx + DELIMITER.length()).trim();
        return ColumnKey.of(table, column);
    }

    public static ColumnKey fromJsonValue(String value, CaseNormalizer normalizer) {
        Objects.requireNonNull(normalizer, "normalizer must not be null");
        if (value == null || value.isBlank()) return ColumnKey.of("", "", normalizer);

        int idx = value.indexOf(DELIMITER);
        String table, column;
        if (idx < 0) { // 단일 토큰 → columnOnly
            table = "";
            column = value.trim();
        } else {
            table = value.substring(0, idx).trim();
            column = value.substring(idx + DELIMITER.length()).trim();
        }
        return ColumnKey.of(table, column, normalizer);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ColumnKey that = (ColumnKey) obj;
        return Objects.equals(canonical, that.canonical);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(canonical);
    }
    
    @Override
    public String toString() {
        return display;
    }
}