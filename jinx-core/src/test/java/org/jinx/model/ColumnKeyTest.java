package org.jinx.model;

import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnKeyTest {

    @Test
    @DisplayName("기본 of(lower): display는 원본 보존, canonical은 소문자 정규화")
    void of_defaultLower() {
        ColumnKey k = ColumnKey.of("Product", "createdAt");
        assertThat(k.display()).isEqualTo("Product::createdAt");
        assertThat(k.canonical()).isEqualTo("product::createdat");
    }

    @Test
    @DisplayName("정책 지정 of(upper): canonical은 대문자 정규화")
    void of_withUpper() {
        ColumnKey k = ColumnKey.of("Product", "createdAt", CaseNormalizer.upper());
        assertThat(k.display()).isEqualTo("Product::createdAt");
        assertThat(k.canonical()).isEqualTo("PRODUCT::CREATEDAT");
    }

    @Test
    @DisplayName("정책 지정 of(preserve): canonical은 대소문자 보존")
    void of_withPreserve() {
        ColumnKey k = ColumnKey.of("Product", "createdAt", CaseNormalizer.preserve());
        assertThat(k.display()).isEqualTo("Product::createdAt");
        assertThat(k.canonical()).isEqualTo("Product::createdAt");
    }

    @Test
    @DisplayName("equals/hashCode: canonical만 비교하므로 대소문자 차이에도 동치(lower)")
    void equals_and_hashcode_withLowerPolicy() {
        ColumnKey a = ColumnKey.of("Product", "createdAt");
        ColumnKey b = ColumnKey.of("product", "CREATEDAT");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        HashSet<ColumnKey> set = new HashSet<>();
        set.add(a);
        set.add(b);
        assertThat(set).hasSize(1);
    }

    @Test
    @DisplayName("JSON 라운드트립(기본 lower): of → toJsonValue → fromJsonValue → 동치")
    void json_roundTrip_defaultLower() {
        ColumnKey original = ColumnKey.of("Product", "createdAt");
        String json = original.toJsonValue();
        ColumnKey restored = ColumnKey.fromJsonValue(json);
        assertThat(restored).isEqualTo(original);
        assertThat(restored.canonical()).isEqualTo("product::createdat");
        assertThat(restored.display()).isEqualTo("Product::createdAt");
    }

    @Test
    @DisplayName("JSON 파싱 단일 토큰: 'createdAt' → table은 빈 문자열, columnOnly로 해석")
    void json_singleToken_parsedAsColumnOnly() {
        ColumnKey k = ColumnKey.fromJsonValue("createdAt");
        assertThat(k.display()).isEqualTo("::createdAt");
        assertThat(k.canonical()).isEqualTo("::createdat");
    }

    @Test
    @DisplayName("JSON 파싱: 공백과 트리밍 처리")
    void json_trim_handling() {
        ColumnKey k = ColumnKey.fromJsonValue("  Product  ::  createdAt  ");
        assertThat(k.display()).isEqualTo("Product::createdAt");
        assertThat(k.canonical()).isEqualTo("product::createdat");
    }

    @Test
    @DisplayName("JSON 파싱: null/blank는 빈 키로 복원")
    void json_null_blank_toEmptyKey() {
        assertThat(ColumnKey.fromJsonValue(null).display()).isEqualTo("::");
        assertThat(ColumnKey.fromJsonValue("   ").display()).isEqualTo("::");
    }

    @Test
    @DisplayName("정책 일치 복원(upper): toJsonValue → fromJsonValue(value, upper) → 동치")
    void json_roundTrip_withUpperPolicy() {
        ColumnKey original = ColumnKey.of("Product", "createdAt", CaseNormalizer.upper());
        String json = original.toJsonValue();
        ColumnKey restored = ColumnKey.fromJsonValue(json, CaseNormalizer.upper());
        assertThat(restored).isEqualTo(original);
        assertThat(restored.canonical()).isEqualTo("PRODUCT::CREATEDAT");
        assertThat(restored.display()).isEqualTo("Product::createdAt");
    }

    @Test
    @DisplayName("정책 불일치 시 equals 실패: upper로 생성한 것을 lower로 복원하면 다름")
    void json_policyMismatch_notEqual() {
        ColumnKey upper = ColumnKey.of("Product", "createdAt", CaseNormalizer.upper());
        ColumnKey restoredLower = ColumnKey.fromJsonValue(upper.toJsonValue(), CaseNormalizer.lower());
        assertThat(restoredLower).isNotEqualTo(upper);
        assertThat(upper.canonical()).isEqualTo("PRODUCT::CREATEDAT");
        assertThat(restoredLower.canonical()).isEqualTo("product::createdat");
    }

    @Test
    @DisplayName("단일 토큰 + 정책 지정: fromJsonValue(value, upper)에서 columnOnly 대문자 정규화")
    void json_singleToken_withPolicy() {
        ColumnKey k = ColumnKey.fromJsonValue("createdAt", CaseNormalizer.upper());
        assertThat(k.display()).isEqualTo("::createdAt");
        assertThat(k.canonical()).isEqualTo("::CREATEDAT");
    }
}
