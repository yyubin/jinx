package org.jinx.naming;

import jakarta.persistence.CheckConstraint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
        // 주의: 기존 규칙을 유지 — childCols만 이름에 반영 (parentCols는 반영하지 않음)
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

    // prefix + norm(table) + "__" + joinNormalizedColumns(cols) -> clamp
    private String buildNameWithColumns(String prefix, String table, List<String> cols) {
        String base = prefix + norm(table) + "__" + joinNormalizedColumns(cols);
        return clampWithHash(base);
    }

    /**
     * 컬럼 리스트에 대해: 정규화 → 정렬(CASE_INSENSITIVE_ORDER) -> '_' 조인
     * 입력이 null/빈이면 빈 문자열 반환
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
     * 정규화 규칙:
     *  - null -> "null"
     *  - 비허용문자([^A-Za-z0-9_]) -> '_'
     *  - 연속 '_' -> 단일 '_'
     *  - 소문자화
     *  - 결과가 빈 문자열이거나 전부 '_'이면 'x'
     */
    private String norm(String s) {
        if (s == null) return "null";
        String x = s.replaceAll("[^A-Za-z0-9_]", "_"); // 비허용자 -> '_'
        x = x.replaceAll("_+", "_");                   // 연속 '_' -> 단일 '_'
        x = x.toLowerCase();
        if (x.isEmpty() || x.chars().allMatch(ch -> ch == '_')) {
            return "x";
        }
        return x;
    }

    // 길이 제한 초과 시: [prefix] + '_' + [hex(hashCode)] 형태로 절단
    private String clampWithHash(String name) {
        if (name.length() <= maxLength) return name;
        String hash = Integer.toHexString(name.hashCode());
        int keep = Math.max(1, maxLength - (hash.length() + 1)); // '_' 포함
        return name.substring(0, keep) + "_" + hash;
    }

    private String safeConstraint(CheckConstraint cc) {
        return (cc == null || cc.constraint() == null) ? "" : cc.constraint();
    }
}
