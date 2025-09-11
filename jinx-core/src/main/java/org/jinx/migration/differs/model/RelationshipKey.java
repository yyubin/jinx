package org.jinx.migration.differs.model;

import org.jinx.model.ColumnKey;
import org.jinx.model.RelationshipModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RelationshipModel의 고유 식별 키.
 * 
 * <p><strong>키 정렬 정책 - 집합 동일성:</strong>
 * <ul>
 *   <li>fkColKeys와 refColKeys는 정렬된 상태로 저장됩니다</li>
 *   <li>컬럼 순서와 무관하게 동일한 컬럼 집합은 같은 키를 생성합니다</li>
 *   <li>예: [team_id, dept_id]와 [dept_id, team_id]는 동일한 키가 됩니다</li>
 *   <li>이는 Map 키로 사용될 때 집합 기반 중복 감지를 위함입니다</li>
 * </ul>
 * 
 * <p><strong>주의사항:</strong>
 * 키 생성 시에는 정렬을 사용하지만, 실제 관계 비교 시에는 순서를 보존합니다.
 * 관계 비교는 NormalizedRel에서 처리하며, 컬럼 순서가 의미적으로 중요합니다.
 */
public record RelationshipKey(
    String fkTableKey,          // normalizer.normalize(fkTableName)
    List<String> fkColKeys,     // columns → ColumnKey.canonical() 리스트 (정렬됨)
    String refTableKey,         // normalizer.normalize(referencedTable)
    List<String> refColKeys     // referencedColumns → ColumnKey.canonical() 리스트 (정렬됨)
) {
    
    public static RelationshipKey of(RelationshipModel relationship, CaseNormalizer normalizer) {
        Objects.requireNonNull(normalizer, "normalizer must not be null");
        
        String fkTableKey = relationship.getFkTableKey(normalizer);
        String refTableKey = relationship.getRefTableKey(normalizer);
        
        var fkCols = relationship.getColumnsKeys(normalizer);
        List<String> fkColKeys = (fkCols == null ? Collections.<ColumnKey>emptyList() : fkCols)
                .stream()
                .map(ColumnKey::canonical)
                .distinct() // 집합 동등성
                .sorted()
                .toList();
                
        var refCols = relationship.getReferencedColumnKeys(normalizer);
        List<String> refColKeys = (refCols == null ? Collections.<ColumnKey>emptyList() : refCols)
                .stream()
                .map(ColumnKey::canonical)
                .distinct()
                .sorted()
                .toList();
                
        return new RelationshipKey(fkTableKey, fkColKeys, refTableKey, refColKeys);
    }
    
    public RelationshipKey {
        fkTableKey = fkTableKey == null ? "" : fkTableKey;
        refTableKey = refTableKey == null ? "" : refTableKey;
        fkColKeys = fkColKeys == null ? Collections.emptyList() : List.copyOf(fkColKeys);
        refColKeys = refColKeys == null ? Collections.emptyList() : List.copyOf(refColKeys);
    }
}