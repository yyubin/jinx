package org.jinx.util;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ConstraintKeys {
    private ConstraintKeys() {
    }

    public static String canonicalKey(String type,
                                      String schema,
                                      String table,
                                      List<String> columns,
                                      String whereOrNull) {
        String ty = norm(type);
        String s  = isBlank(schema) ? "_" : norm(schema);
        String t  = norm(table);
        String c  = columns == null || columns.isEmpty() ? "_" : columns.stream().map(ConstraintKeys::norm).collect(Collectors.joining(","));
        String w  = isBlank(whereOrNull) ? "_" : norm(whereOrNull);
        return ty + "::" + s + "::" + t + "::" + c + "::" + w;
    }

    private static String norm(String s) {
        return s == null ? "null" : s.trim().toLowerCase(Locale.ROOT);
    }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
