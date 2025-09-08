package org.jinx.context;

import jakarta.persistence.CheckConstraint;
import org.jinx.annotation.Constraint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

public class DefaultNaming implements Naming{
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
        String base = "fk_" + norm(childTable) + "__" + String.join("_", sorted(childCols).stream().map(this::norm).toList())
                + "__" + norm(parentTable);
        return clampWithHash(base);
    }

    @Override
    public String pkName(String table, List<String> cols) {
        String base = "pk_" + norm(table) + "__" + String.join("_", sorted(cols).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    @Override
    public String uqName(String table, List<String> cols) {
        String base = "uq_" + norm(table) + "__" + String.join("_", sorted(cols).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    @Override
    public String ixName(String table, List<String> cols) {
        String base = "ix_" + norm(table) + "__" + String.join("_", sorted(cols).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    @Override
    public String ckName(String tableName, List<String> columns) {
        String base = "ck_" + norm(tableName) + "__" + String.join("_", sorted(columns).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    @Override
    public String ckName(String tableName, CheckConstraint constraint) {
        String base = "ck_" + norm(tableName) + "__" + norm(constraint.constraint());
        return clampWithHash(base);
    }

    @Override
    public String nnName(String tableName, List<String> columns) {
        String base = "nn_" + norm(tableName) + "__" + String.join("_", sorted(columns).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    @Override
    public String dfName(String tableName, List<String> columns) {
        String base = "df_" + norm(tableName) + "__" + String.join("_", sorted(columns).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    @Override
    public String autoName(String tableName, List<String> columns) {
        // 프리픽스는 'cn_'(constraint)로 통일된 fallback
        String base = "cn_" + norm(tableName) + "__" + String.join("_", sorted(columns).stream().map(this::norm).toList());
        return clampWithHash(base);
    }

    /**
     * Returns a sorted copy of the column list to ensure deterministic naming
     * regardless of input order (Set→List conversions, etc.)
     */
    private List<String> sorted(List<String> cols) {
        var c = new ArrayList<>(cols);
        Collections.sort(c, String.CASE_INSENSITIVE_ORDER);
        return c;
    }

    private String norm(String s) {
        if (s == null) return "null";
        String x = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (x.isEmpty()) x = "x";
        return x.toLowerCase();
    }

    private String clampWithHash(String name) {
        if (name.length() <= maxLength) return name;
        String hash = Integer.toHexString(name.hashCode());
        int keep = Math.max(1, maxLength - (hash.length() + 1));
        return name.substring(0, keep) + "_" + hash;
    }
}
