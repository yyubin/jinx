package org.jinx.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import lombok.*;
import org.jinx.model.naming.CaseNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class RelationshipModel {
    private RelationshipType type;
    private String tableName; // Added for FK constraint table (where FK is created)
    private List<String> columns;
    private String referencedTable;
    private List<String> referencedColumns;
    private String constraintName;
    private OnDeleteAction onDelete;
    private OnUpdateAction onUpdate;
    @Builder.Default private boolean mapsId = false;
    @Builder.Default private boolean noConstraint = false; // Added for @ForeignKey(NO_CONSTRAINT)
    @Builder.Default private List<CascadeType> cascadeTypes = new ArrayList<>(); // Added for cascade
    @Builder.Default private boolean orphanRemoval = false; // Added for orphanRemoval
    @Builder.Default private FetchType fetchType = FetchType.LAZY; // Added for fetch
    private String sourceAttributeName; // 소스 엔티티의 속성명
    
    // @MapsId 매핑 정보 (지연 처리 패스에서 설정)
    @Builder.Default private Map<String, String> mapsIdBindings = new HashMap<>(); // FK컬럼 → 소유 PK컬럼
    private String mapsIdKeyPath; // @MapsId.value() - 빈 문자열이면 전체 PK 공유, 속성명이면 특정 속성 매핑
    
    // 보조 접근자 메서드들 - 정규화된 키 기반 비교를 위한 뷰
    
    /**
     * FK 테이블명의 정규화된 키를 반환합니다.
     */
    public String getFkTableKey(CaseNormalizer normalizer) {
        return normalizer.normalize(tableName);
    }
    
    /**
     * 참조 테이블명의 정규화된 키를 반환합니다.
     */
    public String getRefTableKey(CaseNormalizer normalizer) {
        return normalizer.normalize(referencedTable);
    }
    
    /**
     * FK 컬럼들의 정규화된 ColumnKey 리스트를 반환합니다.
     * 
     * <p><strong>보장사항:</strong>
     * <ul>
     *   <li>반환되는 모든 ColumnKey는 정규화된 상태입니다 (canonical 기반 equals 적용)</li>
     *   <li>반환되는 리스트는 수정 불가능합니다</li>
     *   <li>컬럼 순서는 원본 순서를 유지합니다</li>
     * </ul>
     * 
     * @param normalizer 컬럼명 정규화에 사용할 정규화기
     * @return 정규화된 ColumnKey들의 불변 리스트
     */
    public List<ColumnKey> getColumnsKeys(CaseNormalizer normalizer) {
        if (columns == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
            columns.stream()
                    .map(column -> ColumnKey.of(tableName, column, normalizer))
                    .collect(Collectors.toList())
        );
    }
    
    /**
     * 참조 컬럼들의 정규화된 ColumnKey 리스트를 반환합니다.
     * 
     * <p><strong>보장사항:</strong>
     * <ul>
     *   <li>반환되는 모든 ColumnKey는 정규화된 상태입니다 (canonical 기반 equals 적용)</li>
     *   <li>반환되는 리스트는 수정 불가능합니다</li>
     *   <li>컬럼 순서는 원본 순서를 유지합니다</li>
     * </ul>
     * 
     * @param normalizer 컬럼명 정규화에 사용할 정규화기
     * @return 정규화된 ColumnKey들의 불변 리스트
     */
    public List<ColumnKey> getReferencedColumnKeys(CaseNormalizer normalizer) {
        if (referencedColumns == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
            referencedColumns.stream()
                    .map(column -> ColumnKey.of(referencedTable, column, normalizer))
                    .collect(Collectors.toList())
        );
    }
}
