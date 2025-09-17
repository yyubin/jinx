package org.jinx.migration.differs;

import org.jinx.model.ConstraintModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

import java.util.*;
import java.util.function.Function;

public class ConstraintDiffer implements EntityComponentDiffer {

    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        var oldConsFiltered = oldEntity.getConstraints().values().stream().toList();
        var newConsFiltered = newEntity.getConstraints().values().stream().toList();

        // 1) old 제약 버킷화 (느슨 키: type + columnSet)
        Map<ConstraintKey, Deque<ConstraintModel>> oldBuckets = bucketize(oldConsFiltered);

        // 2) new 순회하여 1:1 매칭
        List<ConstraintModel> added = new ArrayList<>();
        List<ConstraintPair> matched = new ArrayList<>();
        for (ConstraintModel nc : newConsFiltered) {
            ConstraintKey key = ConstraintKey.of(nc);
            Deque<ConstraintModel> q = oldBuckets.get(key);
            if (q != null && !q.isEmpty()) {
                matched.add(new ConstraintPair(q.pollFirst(), nc));
            } else {
                added.add(nc);
            }
        }

        // 3) 남은 old → dropped
        List<ConstraintModel> dropped = new ArrayList<>();
        for (Deque<ConstraintModel> q : oldBuckets.values()) {
            while (!q.isEmpty()) dropped.add(q.pollFirst());
        }

        // 4) 매칭된 쌍 변경 비교
        for (ConstraintPair p : matched) {
            String change = buildChangeDetail(p.oldC, p.newC);
            if (!change.isEmpty()) {
                result.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.MODIFIED)
                        .constraint(p.newC)
                        .oldConstraint(p.oldC)
                        .changeDetail(change)
                        .build());
            }
        }

        // 5) 추가/삭제 반영
        for (ConstraintModel nc : added) {
            result.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                    .type(DiffResult.ConstraintDiff.Type.ADDED)
                    .constraint(nc)
                    .build());
        }
        for (ConstraintModel oc : dropped) {
            result.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                    .type(DiffResult.ConstraintDiff.Type.DROPPED)
                    .constraint(oc)
                    .build());
        }
    }

    private Map<ConstraintKey, Deque<ConstraintModel>> bucketize(Collection<ConstraintModel> cons) {
        Map<ConstraintKey, Deque<ConstraintModel>> buckets = new HashMap<>();
        for (ConstraintModel c : cons) {
            ConstraintKey k = ConstraintKey.of(c);
            buckets.computeIfAbsent(k, __ -> new ArrayDeque<>()).add(c);
        }
        return buckets;
    }

    static final class ConstraintKey {
        final Object type;
        final Set<String> columnSet;

        private ConstraintKey(Object type, Set<String> columnSet) {
            this.type = type;
            this.columnSet = columnSet;
        }

        static ConstraintKey of(ConstraintModel c) {
            return new ConstraintKey(
                    c.getType(),
                    canonicalColsAsSet(c.getColumns())
            );
        }

        private static Set<String> canonicalColsAsSet(List<String> cols) {
            if (cols == null) return java.util.Collections.emptySet();
            return cols.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }

        private static String normalizeSqlExpr(String sql) {
            if (sql == null) return "";
            String x = sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (x.startsWith("(") && x.endsWith(")") && x.length() > 2) {
                x = x.substring(1, x.length() - 1).trim();
            }
            return x;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ConstraintKey)) return false;
            ConstraintKey k = (ConstraintKey) o;
            return Objects.equals(type, k.type) && Objects.equals(columnSet, k.columnSet);
        }
        @Override
        public int hashCode() { return Objects.hash(type, columnSet); }
    }

    static final class ConstraintPair {
        final ConstraintModel oldC, newC;
        ConstraintPair(ConstraintModel o, ConstraintModel n) { this.oldC = o; this.newC = n; }
    }

    private String buildChangeDetail(ConstraintModel oldC, ConstraintModel newC) {
        StringBuilder d = new StringBuilder();

        if (!eq(oldC.getName(), newC.getName())) {
            d.append("name changed from ").append(oldC.getName()).append(" to ").append(newC.getName()).append("; ");
        }
        if (!eq(oldC.getSchema(), newC.getSchema())) {
            d.append("schema changed from ").append(oldC.getSchema()).append(" to ").append(newC.getSchema()).append("; ");
        }
        if (!eq(oldC.getTableName(), newC.getTableName())) {
            d.append("tableName changed from ").append(oldC.getTableName()).append(" to ").append(newC.getTableName()).append("; ");
        }

        // 컬럼: 집합이 달라지면 변경으로 기록, 순서만 달라지면 무시
        if (!eqListSet(oldC.getColumns(), newC.getColumns())) {
            d.append("columns changed from ").append(oldC.getColumns()).append(" to ").append(newC.getColumns()).append("; ");
        }

        // CHECK / WHERE / OPTIONS 은 비교
        if (!eqStrNorm(oldC.getCheckClause(), newC.getCheckClause())) {
            d.append("checkClause changed; ");
        }
        if (!eqStrNorm(oldC.getWhere(), newC.getWhere())) {
            d.append("where changed; ");
        }
        if (!eqStr(oldC.getOptions(), newC.getOptions())) {
            d.append("options changed from ").append(oldC.getOptions()).append(" to ").append(newC.getOptions()).append("; ");
        }

        if (d.length() > 2) d.setLength(d.length() - 2);
        return d.toString();
    }

    private static boolean eq(Object a, Object b) { return Objects.equals(a, b); }

    private static boolean eqListSet(List<String> a, List<String> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return ConstraintKey.canonicalColsAsSet(a).equals(ConstraintKey.canonicalColsAsSet(b));
    }

    private static boolean eqOptNorm(Optional<String> a, Optional<String> b) {
        String la = a != null && a.isPresent() ? ConstraintKey.normalizeSqlExpr(a.get()) : "";
        String lb = b != null && b.isPresent() ? ConstraintKey.normalizeSqlExpr(b.get()) : "";
        return Objects.equals(la, lb);
    }

    private static boolean eqOpt(Optional<String> a, Optional<String> b) {
        String la = a != null && a.isPresent() ? a.get() : null;
        String lb = b != null && b.isPresent() ? b.get() : null;
        return Objects.equals(la, lb);
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }

    private static boolean eqStrNorm(String a, String b) {
        return Objects.equals(norm(a), norm(b));
    }

    private static boolean eqStr(String a, String b) {
        return Objects.equals(a, b);
    }

}