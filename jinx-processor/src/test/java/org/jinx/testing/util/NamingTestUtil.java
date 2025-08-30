package org.jinx.testing.util;

import java.util.List;

public final class NamingTestUtil {
    public static String fk(String table, List<String> cols, String refTable, List<String> refCols) {
        return "FK_" + table + "_" + String.join("_", cols) + "_" + refTable;
    }
}