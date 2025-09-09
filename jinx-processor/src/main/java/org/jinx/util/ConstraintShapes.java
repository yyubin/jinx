package org.jinx.util;

import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ConstraintShapes {
    private ConstraintShapes() {}

    public static String shapeKey(ConstraintModel c) {
        String table = norm(c.getTableName());
        var cols = c.getColumns().stream().map(ConstraintShapes::norm).toList();

        String colsKey;
        if (c.getType() == ConstraintType.UNIQUE || c.getType() == ConstraintType.PRIMARY_KEY) {
            var sorted = new ArrayList<>(cols);
            Collections.sort(sorted);
            colsKey = String.join(",", sorted);
        } else {
            colsKey = String.join(",", cols);
        }

        String whereKey = c.getWhere() != null && c.getWhere().isPresent()
                ? norm(c.getWhere().get())
                : "_";

        return c.getType() + "|" + table + "|" + colsKey + "|" + whereKey;
    }

    public static String shapeKey(IndexModel ix) {
        // 인덱스는 순서 의미 유지
        String table = norm(ix.getTableName());
        String colsKey = ix.getColumnNames().stream().map(ConstraintShapes::norm)
                .collect(Collectors.joining(","));
        return "IX|" + table + "|" + colsKey;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
