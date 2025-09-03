package org.jinx.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
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
}
