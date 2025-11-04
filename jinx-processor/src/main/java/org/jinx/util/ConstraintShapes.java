package org.jinx.util;

import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Utility class to generate canonical "shape keys" for constraints and indexes.
 * <p>
 * These keys are used to uniquely identify a constraint or index based on its
 * semantic properties (type, table, columns, etc.), allowing for reliable
 * detection of duplicates regardless of the assigned name.
 */
public final class ConstraintShapes {
    private ConstraintShapes() {}

    /**
     * Generates a canonical shape key for a {@link ConstraintModel}.
     * For UNIQUE and PRIMARY_KEY constraints, column order is ignored.
     *
     * @param c The constraint model.
     * @return A unique string representing the constraint's shape.
     */
    public static String shapeKey(ConstraintModel c) {
        String table = norm(c.getTableName());
        List<String> colsInput = c.getColumns() != null ? c.getColumns() : List.of();
        List<String> cols = colsInput.stream().map(ConstraintShapes::norm).toList();

        String colsKey;
        if (c.getType() == ConstraintType.UNIQUE || c.getType() == ConstraintType.PRIMARY_KEY) {
            var sorted = new ArrayList<>(cols);
            Collections.sort(sorted);
            colsKey = String.join(",", sorted);
        } else {
            // Preserve order for other constraint types.
            colsKey = String.join(",", cols);
        }

        String whereKey = normalizeBlankToNull(c.getWhere()) != null
                ? norm(c.getWhere())
                : "_";

        return c.getType() + "|" + table + "|" + colsKey + "|" + whereKey;
    }

    /**
     * Generates a canonical shape key for an {@link IndexModel}.
     * Column order is preserved for indexes.
     *
     * @param ix The index model.
     * @return A unique string representing the index's shape.
     */
    public static String shapeKey(IndexModel ix) {
        // Preserve order for indexes.
        String table = norm(ix.getTableName());
        String colsKey = (ix.getColumnNames() != null ? ix.getColumnNames() : List.<String>of())
                .stream()
                .map(ConstraintShapes::norm)
                .collect(Collectors.joining(","));
        return "IX|" + table + "|" + colsKey;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeBlankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }
}
