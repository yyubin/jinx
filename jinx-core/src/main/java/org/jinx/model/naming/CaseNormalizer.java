package org.jinx.model.naming;

import java.util.Locale;

@FunctionalInterface
public interface CaseNormalizer {
    String normalize(String raw);

    static CaseNormalizer lower()   { return s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    static CaseNormalizer upper()   { return s -> s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
    static CaseNormalizer preserve(){ return s -> s == null ? "" : s.trim(); }
}
