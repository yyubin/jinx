package org.jinx.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * DB식별자 길이를 초과할 때 해시로 축약‑생성해 주는 유틸리티.
 * Dialect별 {@link IdentifierPolicy} 규칙(maxLength·예약어·대소문자)과
 * 접두사(prefix)를 받아 안전한 이름을 돌려준다.
 */
public final class IdentifierUtil {

    private static final int HASH_LEN = 10;   // 잘라 붙일 해시 길이

    private IdentifierUtil() { /* static only */ }

    /**
     * @param prefix  식별자 접두어  (예: "fk" "idx" "chk")
     * @param policy  Dialect별 식별자 정책
     * @param parts   이름을 구성하는 문자열 조각들
     * @return        정책을 만족하는 짧은 식별자
     */
    public static String shorten(String prefix,
                                 IdentifierPolicy policy,
                                 String... parts) {

        String combined = String.join("_", parts);
        String raw = prefix + "_" + combined;

        String normalized = policy.normalizeCase(raw);
        if (normalized.length() <= policy.maxLength() && !policy.isKeyword(normalized)) {
            return normalized;
        }

        String hash = sha256Base64(combined).substring(0, HASH_LEN);
        String shortened = policy.normalizeCase(prefix + "_" + hash);

        return shortened.length() > policy.maxLength()
                ? shortened.substring(0, policy.maxLength())
                : shortened;
    }

    private static String sha256Base64(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // 이론상 SHA‑256 이 없을 수는 거의 없지만, 안전장치
            return Integer.toHexString(input.hashCode());
        }
    }
}
