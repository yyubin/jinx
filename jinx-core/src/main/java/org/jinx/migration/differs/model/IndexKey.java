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
        Objects.requireNonNull(idx, "IndexModel must not be null");
        Objects.requireNonNull(owner, "EntityModel must not be null");
        Objects.requireNonNull(n, "CaseNormalizer must not be null");

        // tableName 결정 + 트리밍
        String rawTable = (idx.getTableName() == null || idx.getTableName().isBlank())
                ? owner.getTableName()
                : idx.getTableName();
        String table = rawTable == null ? "" : rawTable.trim();
        String tableKey = n.normalize(table);

        // 컬럼 순서 보존 + null 요소 방어
        List<String> keyCols = Optional.ofNullable(idx.getColumnNames()).orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(c -> ColumnKey.of(table, c, n).canonical())
                .toList();

        Boolean unique = Boolean.TRUE.equals(idx.getUnique());
        String whereKey = normalizeExpr(idx.getWhere(), n); // 이미 trim/공백 축소 포함

        // type 트리밍 후 정규화
        String typeKey = idx.getType() == null ? null : n.normalize(idx.getType().trim());

        return new IndexKey(tableKey, keyCols, unique, whereKey, typeKey);
    }
    private static String normalizeExpr(String expr, CaseNormalizer n) {
        if (expr == null) return null;
        String compact = expr.trim().replaceAll("\\s+", " ");
        return n.normalize(compact);
    }
}