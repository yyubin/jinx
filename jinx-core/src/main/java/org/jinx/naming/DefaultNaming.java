package org.jinx.naming;

import jakarta.persistence.CheckConstraint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultNaming implements Naming {
    private final int maxLength;

    public DefaultNaming(int maxNameLength) {
        this.maxLength = maxNameLength;
    }

    @Override
    public String foreignKeyColumnName(String ownerName, String referencedPkColumnName) {
        return norm(ownerName) + "_" + norm(referencedPkColumnName);
    }

    @Override
    public String joinTableName(String leftTable, String rightTable) {
        String a = norm(leftTable);
        String b = norm(rightTable);
        String base = (a.compareTo(b) <= 0) ? a + "__" + b : b + "__" + a;
        return clampWithHash("jt_" + base);
    }

    @Override
    public String fkName(String childTable, List<String> childCols, String parentTable, List<String> parentCols) {
        // Note: Maintain the existing rule — only childCols are reflected in the name (parentCols are not).
        String base = "fk_"
                + norm(childTable)
                + "__"
                + joinNormalizedColumns(childCols)
                + "__"
                + norm(parentTable);
        return clampWithHash(base);
    }

    @Override
    public String pkName(String table, List<String> cols) {
        return buildNameWithColumns("pk_", table, cols);
    }

    @Override
    public String uqName(String table, List<String> cols) {
        return buildNameWithColumns("uq_", table, cols);
    }

    @Override
    public String ixName(String table, List<String> cols) {
        return buildNameWithColumns("ix_", table, cols);
    }

    @Override
    public String ckName(String tableName, List<String> columns) {
        return buildNameWithColumns("ck_", tableName, columns);
    }

    @Override
    public String ckName(String tableName, CheckConstraint constraint) {
        String base = "ck_" + norm(tableName) + "__" + norm(safeConstraint(constraint));
        return clampWithHash(base);
    }

    @Override
    public String nnName(String tableName, List<String> columns) {
        return buildNameWithColumns("nn_", tableName, columns);
    }

    @Override
    public String dfName(String tableName, List<String> columns) {
        return buildNameWithColumns("df_", tableName, columns);
    }

    @Override
    public String autoName(String tableName, List<String> columns) {
        return buildNameWithColumns("cn_", tableName, columns);
    }

    // Generates a name from a prefix, table, and columns, then clamps it.
    private String buildNameWithColumns(String prefix, String table, List<String> cols) {
        String base = prefix + norm(table) + "__" + joinNormalizedColumns(cols);
        return clampWithHash(base);
    }

    /**
     * For a list of columns: Normalize -> Sort (CASE_INSENSITIVE_ORDER) -> Join with '_'.
     * Returns an empty string if the input is null or empty.
     */
    private String joinNormalizedColumns(List<String> cols) {
        if (cols == null || cols.isEmpty()) return "";
        List<String> normalized = cols.stream()
                .map(this::norm)
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.sort(normalized, String.CASE_INSENSITIVE_ORDER);
        return String.join("_", normalized);
    }

    /**
     * Normalization rules:
     *  - null -> "null"
     *  - Disallowed characters ([^A-Za-z0-9_]) -> '_'
     *  - Consecutive '_' -> single '_'
     *  - Convert to lowercase
     *  - If the result is an empty string or all '_', it becomes 'x'
     */
    private String norm(String s) {
        if (s == null) return "null";
        String x = s.replaceAll("[^A-Za-z0-9_]", "_"); // Disallowed characters -> '_'
        x = x.replaceAll("_+", "_");                    // Consecutive '_' -> single '_'
        x = x.toLowerCase();
        if (x.isEmpty() || x.chars().allMatch(ch -> ch == '_')) {
            return "x";
        }
        return x;
    }

    // If the length limit is exceeded, truncate to the form: [prefix] + '_' + [hex(hash)].
    // Uses a SHA-256 based hash to minimize collision probability.
    private String clampWithHash(String name) {
        if (name.length() <= maxLength) return name;
        String hash = computeStableHash(name);
        int keep = Math.max(1, maxLength - (hash.length() + 1)); // including the '_' separator
        return name.substring(0, keep) + "_" + hash;
    }

    /**
     * Generates a stable hash based on SHA-256.
     * Has a lower collision probability than String.hashCode().
     */
    private String computeStableHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // Use only the first 4 bytes (8 hex characters).
            return String.format("%02x%02x%02x%02x", hash[0], hash[1], hash[2], hash[3]);
        } catch (NoSuchAlgorithmException e) {
            // Fallback: Use String.hashCode().
            return Integer.toHexString(input.hashCode());
        }
    }

    private String safeConstraint(CheckConstraint cc) {
        return (cc == null || cc.constraint() == null) ? "" : cc.constraint();
    }
}