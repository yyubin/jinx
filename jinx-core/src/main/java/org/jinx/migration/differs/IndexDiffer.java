package org.jinx.migration.differs;

import org.jinx.migration.differs.model.IndexKey;
import org.jinx.migration.differs.model.NormalizedIndex;
import org.jinx.model.ColumnKey;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 인덱스 차이점을 분석하는 클래스.
 *
 * <p><strong>2단계 매칭 전략:</strong>
 * <ol>
 *   <li>1단계: 이름 기반 매칭 (O(N)) - 기존과 동일</li>
 *   <li>2단계: 구조 키 기반 교차 매칭 - RENAME 감지</li>
 * </ol>
 *
 * <p><strong>성능 최적화:</strong>
 * <ul>
 *   <li>NormalizedIndex를 통한 정규화 값 캐싱</li>
 *   <li>LinkedHashMap을 통한 결정적 순서 보장</li>
 *   <li>대소문자/공백 차이로 인한 오탐 방지</li>
 * </ul>
 */
public class IndexDiffer implements EntityComponentDiffer {

    private final CaseNormalizer normalizer;

    public IndexDiffer() {
        this(CaseNormalizer.lower()); // 기본값으로 소문자 정규화 사용
    }

    public IndexDiffer(CaseNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    private static class CollapseWrapper {
        final Map.Entry<String, IndexModel> entry;
        final List<String> collapsedNames = new ArrayList<>();

        CollapseWrapper(Map.Entry<String, IndexModel> entry) {
            this.entry = entry;
        }
    }

    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        Map<String, IndexModel> oldByName = new LinkedHashMap<>(
            Optional.ofNullable(oldEntity.getIndexes()).orElseGet(Map::of)
        );
        Map<String, IndexModel> newByName = new LinkedHashMap<>(
            Optional.ofNullable(newEntity.getIndexes()).orElseGet(Map::of)
        );

        // 1단계: 이름 기준 매칭 (O(N))
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, IndexModel> entry : newByName.entrySet()) {
            String name = entry.getKey();
            IndexModel newIndex = entry.getValue();
            IndexModel oldIndex = oldByName.get(name);

            if (oldIndex == null) {
                // 이름으로 매칭되지 않음 - ADDED로 일단 처리 (2단계에서 재검토)
                result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.ADDED)
                        .index(newIndex)
                        .build());
            } else {
                // 이름 매칭됨 - 구조 비교
                NormalizedIndex nOld = NormalizedIndex.of(oldIndex, oldEntity, normalizer);
                NormalizedIndex nNew = NormalizedIndex.of(newIndex, newEntity, normalizer);

                if (!nOld.equalTo(nNew)) {
                    result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                            .type(DiffResult.IndexDiff.Type.MODIFIED)
                            .index(newIndex)
                            .oldIndex(oldIndex)
                            .changeDetail(getIndexChangeDetail(nOld, nNew, newEntity))
                            .build());
                }
            }
            seen.add(name);
        }

        // 2단계: 이름으로 매칭되지 않은 것들에 대한 구조 키 기반 교차 매칭
        List<Map.Entry<String, IndexModel>> droppedByName = oldByName.entrySet().stream()
                .filter(entry -> !seen.contains(entry.getKey()))
                .collect(Collectors.toList());

        // 구조 키 기반 매핑 생성 (DROPPED 후보들)
        Map<IndexKey, CollapseWrapper> oldDroppedByKey = droppedByName.stream()
                .collect(Collectors.toMap(
                        entry -> IndexKey.of(entry.getValue(), oldEntity, normalizer),
                        CollapseWrapper::new,
                        (a, b) -> {
                            a.collapsedNames.add(b.entry.getKey());
                            return a;
                        },
                        LinkedHashMap::new
                ));

        // 1단계에서 ADDED로 처리된 것들을 재검토
        List<DiffResult.IndexDiff> addedDiffs = result.getIndexDiffs().stream()
                .filter(diff -> diff.getType() == DiffResult.IndexDiff.Type.ADDED)
                .collect(Collectors.toList());

        // new 쪽 키 중복 검사
        var newKeyCounts = newByName.values().stream()
            .collect(Collectors.groupingBy(idx -> IndexKey.of(idx, newEntity, normalizer), Collectors.counting()));
        newKeyCounts.forEach((k, cnt) -> {
            if (cnt > 1) {
                result.getWarnings().add("Duplicate new indexes collapsed by key: " + k + " (count=" + cnt + ")");
            }
        });

        for (DiffResult.IndexDiff addedDiff : addedDiffs) {
            IndexModel newIndex = addedDiff.getIndex();
            IndexKey newKey = IndexKey.of(newIndex, newEntity, normalizer);
            CollapseWrapper matchedWrapper = oldDroppedByKey.remove(newKey);

            if (matchedWrapper != null) {
                // 구조가 동일한 old 인덱스 발견 - ADDED를 RENAME으로 변경
                result.getIndexDiffs().remove(addedDiff);
                IndexModel oldIndex = matchedWrapper.entry.getValue();

                StringBuilder changeDetail = new StringBuilder("[RENAME] index name changed from ")
                    .append(oldIndex.getIndexName()).append(" to ").append(newIndex.getIndexName());

                if (!matchedWrapper.collapsedNames.isEmpty()) {
                    changeDetail.append(". [WARN] Duplicate dropped-candidates collapsed: ")
                                .append(matchedWrapper.collapsedNames);
                }

                result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.MODIFIED)
                        .index(newIndex)
                        .oldIndex(oldIndex)
                        .changeDetail(changeDetail.toString())
                        .build());
            }
        }

        // 구조 키 매칭으로도 찾지 못한 old 인덱스들은 진짜 DROPPED
        for (CollapseWrapper wrapper : oldDroppedByKey.values()) {
            result.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                    .type(DiffResult.IndexDiff.Type.DROPPED)
                    .index(wrapper.entry.getValue())
                    .build());
        }
    }

    private String getIndexChangeDetail(NormalizedIndex oldNormalized, NormalizedIndex newNormalized, EntityModel newEntity) {
        StringBuilder detail = new StringBuilder();

        IndexKey oldKey = oldNormalized.getKey();
        IndexKey newKey = newNormalized.getKey();
        IndexModel oldIndex = oldNormalized.getOriginal();
        IndexModel newIndex = newNormalized.getOriginal();

        // 테이블 변경 (거의 없겠지만)
        if (!Objects.equals(oldKey.tableKey(), newKey.tableKey())) {
            detail.append("table changed from ").append(oldIndex.getTableName())
                  .append(" to ").append(newIndex.getTableName()).append("; ");
        }

        // 컬럼 변경 - 순서 중요
        if (!Objects.equals(oldKey.keyCols(), newKey.keyCols())) {
            detail.append("columns changed from ").append(oldIndex.getColumnNames())
                  .append(" to ").append(newIndex.getColumnNames()).append("; ");
        }

        if (!Objects.equals(oldKey.unique(), newKey.unique())) {
            detail.append("unique changed from ").append(oldKey.unique())
                  .append(" to ").append(newKey.unique()).append("; ");
        }
        if (!Objects.equals(oldKey.whereKey(), newKey.whereKey())) {
            detail.append("where changed; ");
        }
        if (!Objects.equals(oldKey.typeKey(), newKey.typeKey())) {
            detail.append("type changed from ").append(oldKey.typeKey())
                  .append(" to ").append(newKey.typeKey()).append("; ");
        }

        // 중복 컬럼 경고 (정규화 기준)
        var table = Optional.ofNullable(newIndex.getTableName()).orElse(newEntity.getTableName());
        var dups = Optional.ofNullable(newIndex.getColumnNames()).orElseGet(List::of).stream()
            .map(c -> ColumnKey.of(table, c, normalizer).canonical())
            .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
            .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!dups.isEmpty()) {
            detail.append("[WARN] Duplicate columns(normalized): ").append(dups).append("; ");
        }

        if (detail.length() >= 2) {
            detail.setLength(detail.length() - 2);
        }

        return detail.toString();
    }
}
