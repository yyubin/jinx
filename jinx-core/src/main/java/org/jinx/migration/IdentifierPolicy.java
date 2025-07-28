package org.jinx.migration;

public interface IdentifierPolicy {
    int  maxLength();                      // 30, 63, 64, 128 …
    String quote(String raw);              // `foo`, "foo", [foo]
    String normalizeCase(String raw);      // Oracle → toUpperCase
    boolean isKeyword(String raw);         // 예약어 확인
}
