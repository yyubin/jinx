package org.jinx.migration.differs.model;

import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.Objects;

/**
 * IndexModel의 정규화된 키를 캐시하여 반복 계산을 방지하는 래퍼 클래스.
 * 
 * <p><strong>성능 최적화:</strong>
 * IndexDiffer에서 동일한 정규화 값을 여러 번 계산하는 것을 방지하여
 * 성능을 최적화합니다. 특히 대량의 인덱스 비교 시 효과적입니다.
 * 
 * <p><strong>정규화 캐싱:</strong>
 * <ul>
 *   <li>IndexKey 생성 시 정규화 계산을 한 번만 수행</li>
 *   <li>비교 작업에서 캐시된 값을 재사용</li>
 *   <li>불변 객체로 안전한 캐싱 보장</li>
 * </ul>
 */
public final class NormalizedIndex {
    private final IndexModel original;
    private final IndexKey key;
    
    private NormalizedIndex(IndexModel original, IndexKey key) {
        this.original = original;
        this.key = key;
    }
    
    /**
     * IndexModel과 EntityModel로부터 NormalizedIndex를 생성합니다.
     * 
     * @param idx IndexModel 인스턴스 
     * @param owner 인덱스를 소유한 엔티티 모델
     * @param normalizer 케이스 정규화기
     * @return 생성된 NormalizedIndex
     */
    public static NormalizedIndex of(IndexModel idx, EntityModel owner, CaseNormalizer normalizer) {
        Objects.requireNonNull(idx, "IndexModel must not be null");
        Objects.requireNonNull(owner, "EntityModel must not be null");
        Objects.requireNonNull(normalizer, "CaseNormalizer must not be null");
        return new NormalizedIndex(idx, IndexKey.of(idx, owner, normalizer));
    }
    
    /**
     * 원본 IndexModel을 반환합니다.
     * 
     * @return 원본 IndexModel
     */
    public IndexModel getOriginal() {
        return original;
    }
    
    /**
     * 캐시된 정규화 키를 반환합니다.
     * 
     * @return 정규화된 IndexKey
     */
    public IndexKey getKey() {
        return key;
    }
    
    /**
     * 두 NormalizedIndex 간의 동등성 비교 (정규화된 키 기반).
     * 
     * @param other 비교할 다른 NormalizedIndex
     * @return 정규화된 키가 동일하면 true
     */
    public boolean equalTo(NormalizedIndex other) {
        if (this == other) return true;
        if (other == null) return false;
        return Objects.equals(this.key, other.key);
    }
    
    @Override
    public String toString() {
        return "NormalizedIndex{" +
                "key=" + key +
                ", original=" + original +
                '}';
    }
}