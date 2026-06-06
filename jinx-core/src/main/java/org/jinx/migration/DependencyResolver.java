package org.jinx.migration;

import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FK 의존성 기반 위상정렬 (Kahn's BFS).
 *
 * <p>{@link #sortByFkDependency}는 부모 테이블(참조 대상)이 먼저 오는 순서를 반환합니다.
 * CREATE 순서로 사용하고, DROP 순서는 호출부에서 {@code .reversed()}를 적용합니다.
 *
 * <p>입력 집합 내 테이블 간 FK만 고려하며, 사이클 감지 시 경고를 출력하고 원본 순서를 유지합니다.
 */
public final class DependencyResolver {

    private DependencyResolver() {}

    /**
     * FK 의존성 기준으로 테이블 목록을 정렬합니다.
     *
     * <ul>
     *   <li>부모 테이블(referencedTable)이 자식 테이블(FK 보유)보다 앞에 위치합니다.</li>
     *   <li>{@code noConstraint=true} 관계는 실제 FK가 없으므로 의존성 집계에서 제외됩니다.</li>
     *   <li>입력 집합 밖 테이블에 대한 참조 및 자기 참조는 무시합니다.</li>
     *   <li>사이클 감지 시 {@code System.err}에 경고를 출력하고 원본 리스트를 반환합니다.</li>
     * </ul>
     *
     * @param tables 정렬할 테이블 목록
     * @return FK 의존성 기준으로 정렬된 새 리스트 (원본 불변)
     */
    public static List<EntityModel> sortByFkDependency(List<EntityModel> tables) {
        if (tables == null) {
            return List.of();
        }
        if (tables.size() <= 1) {
            return tables;
        }

        // 테이블명(소문자) → EntityModel 맵 (입력 순서 보존)
        Map<String, EntityModel> byName = new LinkedHashMap<>();
        for (EntityModel entity : tables) {
            if (entity.getTableName() != null) {
                byName.put(entity.getTableName().toLowerCase(Locale.ROOT), entity);
            }
        }

        if (byName.size() <= 1) {
            return tables;
        }

        Set<String> scope = byName.keySet();

        // in-degree: 선행 부모 수
        // successors: parent → 이 parent가 처리된 후 in-degree를 줄일 child 집합
        // LinkedHashSet으로 중복 엣지(동일 부모를 향한 FK 복수 개)를 방지하고 삽입 순서를 유지
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> successors = new HashMap<>();

        for (String name : scope) {
            inDegree.put(name, 0);
            successors.put(name, new LinkedHashSet<>());
        }

        for (EntityModel entity : tables) {
            if (entity.getTableName() == null) continue;
            String child = entity.getTableName().toLowerCase(Locale.ROOT);

            Map<String, RelationshipModel> rels = entity.getRelationships();
            if (rels == null) continue;

            for (RelationshipModel rel : rels.values()) {
                if (rel.isNoConstraint() || rel.getReferencedTable() == null) continue;
                String parent = rel.getReferencedTable().toLowerCase(Locale.ROOT);

                if (!scope.contains(parent) || parent.equals(child)) continue;

                // 동일 parent→child 엣지가 이미 추가되었으면 in-degree를 중복 증가시키지 않음
                if (successors.get(parent).add(child)) {
                    inDegree.merge(child, 1, Integer::sum);
                }
            }
        }

        // Kahn's BFS: in-degree 0인 노드(의존성 없는 부모)부터 처리
        // byName(LinkedHashMap) 삽입 순서로 초기화해 독립 테이블 간 출력 순서를 결정론적으로 유지
        Queue<String> queue = new ArrayDeque<>();
        for (String name : byName.keySet()) {
            if (inDegree.get(name) == 0) queue.add(name);
        }

        List<EntityModel> sorted = new ArrayList<>(tables.size());
        while (!queue.isEmpty()) {
            String node = queue.poll();
            sorted.add(byName.get(node));
            for (String child : successors.get(node)) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }

        // 처리된 노드 수가 입력보다 적으면 사이클 존재
        if (sorted.size() != byName.size()) {
            Set<String> cycleNodes = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            System.err.println("[jinx] WARNING: FK dependency cycle detected among tables: "
                    + cycleNodes + ". Falling back to original order.");
            return tables;
        }

        return sorted;
    }
}
