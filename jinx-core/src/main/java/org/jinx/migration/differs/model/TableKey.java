package org.jinx.migration.differs.model;

import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public record TableKey(Set<String> pkColKeys, List<Long> colSig) {
    public TableKey(Set<String> pkColKeys, List<Long> colSig) {
        this.pkColKeys = (pkColKeys == null) ? Set.of() : Set.copyOf(pkColKeys);
        this.colSig = (colSig == null) ? List.of() : List.copyOf(colSig);
    }

    public static TableKey of(EntityModel e, CaseNormalizer n) {
        // PK 집합
        Set<String> pk = e.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(n::normalize)
                .collect(Collectors.toCollection(TreeSet::new)); // 결정적 순서 -> TreeSet

        // 전체 컬럼 해시 정렬 리스트(중복 보존)
        List<Long> sig = e.getColumns().values().stream()
                .map(ColumnModel::getAttributeHash)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        return new TableKey(pk, sig);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableKey k)) return false;
        return Objects.equals(pkColKeys, k.pkColKeys) &&
                Objects.equals(colSig, k.colSig);
    }
    @Override
    public int hashCode() {
        return Objects.hash(pkColKeys, colSig);
    }
    @Override
    public String toString() {
        return "TableKey{pk=" + pkColKeys + ", sig=" + colSig + "}";
    }
}
