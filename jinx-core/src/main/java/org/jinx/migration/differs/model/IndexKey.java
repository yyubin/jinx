package org.jinx.migration.differs.model;

import org.jinx.model.ColumnKey;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record IndexKey(
    String tableKey,
    List<String> keyCols,    // 순서 보존
    Boolean unique,
    String whereKey,
    String typeKey
) {
    public static IndexKey of(IndexModel idx, EntityModel owner, CaseNormalizer n) {
        Objects.requireNonNull(n, "normalizer must not be null");
        String table = (idx.getTableName() == null || idx.getTableName().isBlank())
                ? owner.getTableName() : idx.getTableName();
        String tableKey = n.normalize(Objects.toString(table, ""));

        List<String> keyCols = Optional.ofNullable(idx.getColumnNames()).orElseGet(List::of)
                .stream().map(c -> ColumnKey.of(table, c, n).canonical()).toList();

        Boolean unique = Boolean.TRUE.equals(idx.getUnique());
        String whereKey = normalizeExpr(idx.getWhere(), n); // 모델에 없으면 null 그대로
        String typeKey  = idx.getType() == null ? null : n.normalize(idx.getType());

        return new IndexKey(tableKey, keyCols, unique, whereKey, typeKey);
    }
    private static String normalizeExpr(String expr, CaseNormalizer n) {
        if (expr == null) return null;
        String compact = expr.trim().replaceAll("\s+", " ");
        return n.normalize(compact);
    }
}