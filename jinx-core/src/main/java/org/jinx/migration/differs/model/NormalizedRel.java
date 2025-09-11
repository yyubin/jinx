package org.jinx.migration.differs.model;

import org.jinx.migration.differs.RelationshipDiffer;
import org.jinx.model.ColumnKey;
import org.jinx.model.RelationshipModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.List;
import java.util.Objects;

/**
 * RelationshipModel의 정규화된 필드들을 캐시하여 반복 계산을 방지하는 래퍼 클래스.
 * 
 * <p><strong>순서 보존 정책 - 리스트 동일성:</strong>
 * <ul>
 *   <li>columnsKeys와 referencedColumnsKeys는 원본 모델의 순서를 보존합니다</li>
 *   <li>컬럼 순서가 관계 정의에서 의미적으로 중요하기 때문입니다</li>
 *   <li>예: [team_id, dept_id]와 [dept_id, team_id]는 서로 다른 관계로 비교됩니다</li>
 *   <li>이는 실제 DDL 생성 시 컬럼 순서가 FK 제약조건에 영향을 주기 때문입니다</li>
 * </ul>
 * 
 * <p><strong>RelationshipKey와의 차이점:</strong>
 * <ul>
 *   <li>RelationshipKey: 집합 동일성 (정렬된 키, 중복 감지용)</li>
 *   <li>NormalizedRel: 리스트 동일성 (순서 보존, 실제 비교용)</li>
 * </ul>
 * 
 * <p>isRelationshipEqual()에서 동일한 정규화 값을 여러 번 계산하는 것을 방지하여
 * 성능을 미세하게 최적화합니다. 특히 대량의 관계 비교 시 효과적입니다.
 */
public final class NormalizedRel {
    private final RelationshipModel original;
    
    // 캐시된 정규화 값들
    private final String fkTableKey;
    private final String refTableKey;
    private final List<ColumnKey> columnsKeys;
    private final List<ColumnKey> referencedColumnsKeys;
    private final String normalizedConstraintName;
    
    private NormalizedRel(RelationshipModel original, CaseNormalizer normalizer) {
        this.original = original;
        
        // 정규화 값들을 한 번만 계산하고 캐시
        this.fkTableKey = original.getFkTableKey(normalizer);
        this.refTableKey = original.getRefTableKey(normalizer);
        
        // 방어적 복사로 캐시 불변성 보장
        List<ColumnKey> cols = original.getColumnsKeys(normalizer);
        this.columnsKeys = (cols == null) ? null : List.copyOf(cols);
        List<ColumnKey> refCols = original.getReferencedColumnKeys(normalizer);
        this.referencedColumnsKeys = (refCols == null) ? null : List.copyOf(refCols);
        
        // 제약명: 양끝 공백 트리밍 + 정규화
        String cName = original.getConstraintName();
        this.normalizedConstraintName = (cName == null) ? null : normalizer.normalize(cName.trim());
    }
    
    public static NormalizedRel of(RelationshipModel rel, CaseNormalizer normalizer) {
        Objects.requireNonNull(rel, "RelationshipModel must not be null");
        Objects.requireNonNull(normalizer, "CaseNormalizer must not be null");
        return new NormalizedRel(rel, normalizer);
    }
    
    // 원본 모델 접근
    public RelationshipModel getOriginal() {
        return original;
    }
    
    // 캐시된 정규화 값들 접근
    public String getFkTableKey() {
        return fkTableKey;
    }
    
    public String getRefTableKey() {
        return refTableKey;
    }
    
    public List<ColumnKey> getColumnsKeys() {
        return columnsKeys;
    }
    
    public List<ColumnKey> getReferencedColumnsKeys() {
        return referencedColumnsKeys;
    }
    
    public String getNormalizedConstraintName() {
        return normalizedConstraintName;
    }
    
    /**
     * 두 NormalizedRel 간의 동등성 비교 (정규화된 값들 기반).
     * 
     * @param other 비교할 다른 NormalizedRel
     * @return 정규화된 필드가 모두 동일하면 true
     */
    public boolean isEqualNormalized(NormalizedRel other) {
        if (this == other) return true;
        if (other == null) return false;
        
        RelationshipModel oldRel = this.original;
        RelationshipModel newRel = other.original;
        
        // 정규화된 키 기반 비교로 대소문자/공백 오탐 제거
        return Objects.equals(oldRel.getType(), newRel.getType()) &&
                Objects.equals(this.fkTableKey, other.fkTableKey) &&
                Objects.equals(this.refTableKey, other.refTableKey) &&
                compareColumnKeys(this.columnsKeys, other.columnsKeys) &&
                compareColumnKeys(this.referencedColumnsKeys, other.referencedColumnsKeys) &&
                Objects.equals(this.normalizedConstraintName, other.normalizedConstraintName) &&
                Objects.equals(oldRel.getOnDelete(), newRel.getOnDelete()) &&
                Objects.equals(oldRel.getOnUpdate(), newRel.getOnUpdate()) &&
                oldRel.isMapsId() == newRel.isMapsId() &&
                oldRel.isNoConstraint() == newRel.isNoConstraint() &&
                Objects.equals(oldRel.getMapsIdBindings(), newRel.getMapsIdBindings()) &&
                Objects.equals(oldRel.getMapsIdKeyPath(), newRel.getMapsIdKeyPath()) &&
                RelationshipDiffer.compareCascadeTypes(oldRel.getCascadeTypes(), newRel.getCascadeTypes()) &&
                oldRel.isOrphanRemoval() == newRel.isOrphanRemoval() &&
                Objects.equals(oldRel.getFetchType(), newRel.getFetchType()) &&
                Objects.equals(oldRel.getSourceAttributeName(), newRel.getSourceAttributeName());
    }
    
    private boolean compareColumnKeys(List<ColumnKey> oldKeys, List<ColumnKey> newKeys) {
        if (oldKeys == null && newKeys == null) return true;
        if (oldKeys == null || newKeys == null) return false;
        if (oldKeys.size() != newKeys.size()) return false;
        
        // ColumnKey는 정규화된 상태이며 canonical 기반 equals를 사용, 이 비교는 순서를 보존함
        for (int i = 0; i < oldKeys.size(); i++) {
            if (!Objects.equals(oldKeys.get(i), newKeys.get(i))) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public String toString() {
        return "NormalizedRel{" +
                "fkTableKey='" + fkTableKey + '\'' +
                ", refTableKey='" + refTableKey + '\'' +
                ", columnsKeys=" + columnsKeys +
                ", referencedColumnsKeys=" + referencedColumnsKeys +
                ", normalizedConstraintName='" + normalizedConstraintName + '\'' +
                ", original=" + original +
                '}';
    }
}