package org.jinx.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierUtilTest {

    // 테스트용으로 사용할 간단한 IdentifierPolicy 구현체
    record TestIdentifierPolicy(int maxLength, boolean toUpperCase, Set<String> keywords) implements IdentifierPolicy {
        @Override
        public String quote(String raw) {
            return "";
        }

        @Override
        public String normalizeCase(String identifier) {
            return toUpperCase ? identifier.toUpperCase() : identifier.toLowerCase();
        }
        @Override
        public boolean isKeyword(String identifier) {
            return keywords.contains(normalizeCase(identifier));
        }
    }

    private String getExpectedHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // 테스트 환경에서는 SHA-256이 항상 있어야 함
        }
    }

    @Test
    @DisplayName("식별자 길이가 충분할 때, 정규화된 이름을 그대로 반환해야 한다")
    void shorten_whenNameIsShortEnough_returnsNormalizedName() {
        // Given
        IdentifierPolicy policy = new TestIdentifierPolicy(30, false, Set.of());
        String prefix = "fk";
        String[] parts = {"users", "user_id"};

        // When
        String result = IdentifierUtil.shorten(prefix, policy, parts);

        // Then
        assertThat(result).isEqualTo("fk_users_user_id");
    }

    @Test
    @DisplayName("식별자 길이가 너무 길 때, 해시 기반으로 축약된 이름을 반환해야 한다")
    void shorten_whenNameIsTooLong_returnsHashedName() {
        // Given
        IdentifierPolicy policy = new TestIdentifierPolicy(30, false, Set.of());
        String prefix = "fk";
        String[] parts = {"a_very_long_table_name_that_will_definitely_exceed_the_limit", "and_a_very_long_column_name"};
        String combinedParts = String.join("_", parts);
        String expectedHash = getExpectedHash(combinedParts).substring(0, 10).toLowerCase();

        // When
        String result = IdentifierUtil.shorten(prefix, policy, parts);

        // Then
        assertThat(result).isEqualTo("fk_" + expectedHash);
        assertThat(result.length()).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("식별자가 예약어일 때, 해시 기반으로 축약된 이름을 반환해야 한다")
    void shorten_whenNameIsAKeyword_returnsHashedName() {
        // Given
        IdentifierPolicy policy = new TestIdentifierPolicy(30, false, Set.of("idx_user_select"));
        String prefix = "idx";
        String[] parts = {"user", "select"};
        String combinedParts = String.join("_", parts);

        String expectedHash = getExpectedHash(combinedParts).substring(0, 10).toLowerCase();

        // When
        String result = IdentifierUtil.shorten(prefix, policy, parts);

        // Then
        assertThat(result).isEqualTo("idx_" + expectedHash);
    }

    @Test
    @DisplayName("축약된 이름도 최대 길이를 초과할 때, 최대 길이에 맞게 잘라내야 한다")
    void shorten_whenHashedNameIsStillTooLong_returnsTruncatedName() {
        // Given
        IdentifierPolicy policy = new TestIdentifierPolicy(20, false, Set.of());
        String prefix = "a_very_long_prefix"; // prefix (18) + _ (1) + hash (10) > 20
        String[] parts = {"some_column"};
        String combinedParts = String.join("_", parts);

        String hash = getExpectedHash(combinedParts).substring(0, 10).toLowerCase();
        String expectedHashedName = "a_very_long_prefix_" + hash;

        // When
        String result = IdentifierUtil.shorten(prefix, policy, parts);

        // Then
        assertThat(result).isEqualTo(expectedHashedName.substring(0, 20)); // Truncated to 20
        assertThat(result.length()).isEqualTo(20);
    }

    @Test
    @DisplayName("대문자 정책일 때, 정규화된 이름을 대문자로 반환해야 한다")
    void shorten_withUpperCasePolicy_returnsUpperCaseName() {
        // Given
        IdentifierPolicy policy = new TestIdentifierPolicy(30, true, Set.of());
        String prefix = "fk";
        String[] parts = {"Users", "UserId"};

        // When
        String result = IdentifierUtil.shorten(prefix, policy, parts);

        // Then
        assertThat(result).isEqualTo("FK_USERS_USERID");
    }
}