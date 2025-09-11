package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import org.jinx.migration.differs.model.NormalizedRel;
import org.jinx.migration.differs.model.RelationshipKey;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RelationshipDiffer implements EntityComponentDiffer {
    private final CaseNormalizer normalizer;
    
    public RelationshipDiffer() {
        this(CaseNormalizer.lower()); // 기본값으로 소문자 정규화 사용
    }
    
    public RelationshipDiffer(CaseNormalizer normalizer) {
        this.normalizer = normalizer;
    }
    
    /**
     * Null-safe normalizer helper: null 입력에 대해 NPE를 던지지 않고 null을 반환
     */
    private String nzNorm(String s) {
        return s == null ? null : normalizer.normalize(s);
    }
    
    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        Map<RelationshipKey, RelationshipModel> oldRelMap = buildRelationshipMap(oldEntity, result, "old");
        Map<RelationshipKey, RelationshipModel> newRelMap = buildRelationshipMap(newEntity, result, "new");

        // Process new and modified relationships
        newRelMap.forEach((key, newRel) -> {
            RelationshipModel oldRel = oldRelMap.get(key);
            if (oldRel == null) {
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.ADDED)
                        .relationship(newRel)
                        .build());
            } else if (!isRelationshipEqual(oldRel, newRel)) {
                String changeDetail = getRelationshipChangeDetail(oldRel, newRel);
                boolean requiresDropAdd = requiresDropAdd(oldRel, newRel);
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.MODIFIED)
                        .relationship(newRel)
                        .oldRelationship(oldRel)
                        .changeDetail(changeDetail)
                        .requiresDropAdd(requiresDropAdd)
                        .build());
                analyzeRelationshipChanges(oldRel, newRel, result);
            }
        });

        // Process dropped relationships
        oldRelMap.forEach((key, oldRel) -> {
            if (oldRel.getType() != null && !newRelMap.containsKey(key)) {
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.DROPPED)
                        .relationship(oldRel)
                        .build());
            }
        });
    }

    private Map<RelationshipKey, RelationshipModel> buildRelationshipMap(EntityModel entity, DiffResult.ModifiedEntity result, String entityType) {
        if (entity.getRelationships() == null || entity.getRelationships().isEmpty()) {
            return new LinkedHashMap<>();
        }
        
        Map<RelationshipKey, RelationshipModel> relationshipMap = new LinkedHashMap<>();
        entity.getRelationships().values().forEach(rel -> {
            RelationshipKey key = RelationshipKey.of(rel, normalizer);
            if (relationshipMap.containsKey(key)) {
                // 중복 관계 키 감지 - 의미적 충돌 경고
                RelationshipModel existing = relationshipMap.get(key);
                String warningMessage = String.format(
                    "Duplicate relationships collapsed by key in %s entity '%s': " +
                    "Key=%s, First=[attr=%s, columns=%s], Second=[attr=%s, columns=%s]. " +
                    "Second relationship will overwrite the first. Review relationship definitions to avoid conflicts.",
                    entityType,
                    entity.getEntityName(),
                    key,
                    existing.getSourceAttributeName() != null ? existing.getSourceAttributeName() : "unknown",
                    existing.getColumns(),
                    rel.getSourceAttributeName() != null ? rel.getSourceAttributeName() : "unknown",
                    rel.getColumns()
                );
                result.getWarnings().add(warningMessage);
            }
            relationshipMap.put(key, rel);
        });
        return relationshipMap;
    }

    private boolean isRelationshipEqual(RelationshipModel oldRel, RelationshipModel newRel) {
        // NormalizedRel을 사용하여 정규화 계산을 한 번만 수행 (성능 최적화)
        NormalizedRel oldNormalized = NormalizedRel.of(oldRel, normalizer);
        NormalizedRel newNormalized = NormalizedRel.of(newRel, normalizer);
        return oldNormalized.isEqualNormalized(newNormalized);
    }

    private boolean compareColumnKeys(List<org.jinx.model.ColumnKey> oldKeys, List<org.jinx.model.ColumnKey> newKeys) {
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

    public static boolean compareCascadeTypes(List<CascadeType> oldTypes, List<CascadeType> newTypes) {
        // EnumSet으로 변환하여 순서와 무관한 비교
        EnumSet<CascadeType> oldSet = oldTypes == null || oldTypes.isEmpty() 
            ? EnumSet.noneOf(CascadeType.class) 
            : EnumSet.copyOf(oldTypes);
        EnumSet<CascadeType> newSet = newTypes == null || newTypes.isEmpty() 
            ? EnumSet.noneOf(CascadeType.class) 
            : EnumSet.copyOf(newTypes);
        return Objects.equals(oldSet, newSet);
    }

    /**
     * 구조적 변경(DDL DROP/ADD 필요)인지 판단
     * 구조적: tableName, columns, referencedTable/Columns, constraintName, onDelete/onUpdate, noConstraint
     * 비구조적: type, cascadeTypes, orphanRemoval, fetchType
     * mapsId*: PK 승격 영향 시에는 구조적
     */
    private boolean requiresDropAdd(RelationshipModel oldRel, RelationshipModel newRel) {
        // NormalizedRel을 사용하여 정규화 계산 최적화
        NormalizedRel oldNormalized = NormalizedRel.of(oldRel, normalizer);
        NormalizedRel newNormalized = NormalizedRel.of(newRel, normalizer);
        
        // 구조적 변경 검사
        if (!Objects.equals(oldNormalized.getFkTableKey(), newNormalized.getFkTableKey())) return true;
        if (!compareColumnKeys(oldNormalized.getColumnsKeys(), newNormalized.getColumnsKeys())) return true;
        if (!Objects.equals(oldNormalized.getRefTableKey(), newNormalized.getRefTableKey())) return true;
        if (!compareColumnKeys(oldNormalized.getReferencedColumnsKeys(), newNormalized.getReferencedColumnsKeys())) return true;
        if (!Objects.equals(oldNormalized.getNormalizedConstraintName(), newNormalized.getNormalizedConstraintName())) return true;
        if (!Objects.equals(oldRel.getOnDelete(), newRel.getOnDelete())) return true;
        if (!Objects.equals(oldRel.getOnUpdate(), newRel.getOnUpdate())) return true;
        if (oldRel.isNoConstraint() != newRel.isNoConstraint()) return true;
        
        // mapsId 변경은 PK 승격에 영향을 줄 수 있으므로 구조적 변경으로 간주
        if (oldRel.isMapsId() != newRel.isMapsId()) return true;
        if (!Objects.equals(oldRel.getMapsIdBindings(), newRel.getMapsIdBindings())) return true;
        if (!Objects.equals(oldRel.getMapsIdKeyPath(), newRel.getMapsIdKeyPath())) return true;
        
        return false; // 비구조적 변경만 있음
    }

    private String getRelationshipChangeDetail(RelationshipModel oldRel, RelationshipModel newRel) {
        StringBuilder structural = new StringBuilder(); // 구조적 변경 (DDL 영향)
        StringBuilder behavioral = new StringBuilder(); // 비구조적 변경 (런타임 의미)
        
        // NormalizedRel을 사용하여 정규화 계산 최적화
        NormalizedRel oldNormalized = NormalizedRel.of(oldRel, normalizer);
        NormalizedRel newNormalized = NormalizedRel.of(newRel, normalizer);
        
        // === 구조적 변경들 (DDL DROP/ADD 필요) ===
        
        // 정규화된 테이블명 비교 (하지만 표시는 원본으로)
        if (!Objects.equals(oldNormalized.getFkTableKey(), newNormalized.getFkTableKey())) {
            structural.append("tableName changed from ").append(oldRel.getTableName()).append(" to ").append(newRel.getTableName()).append("; ");
        }
        
        // 정규화된 컬럼 비교 (하지만 표시는 원본으로)
        if (!compareColumnKeys(oldNormalized.getColumnsKeys(), newNormalized.getColumnsKeys())) {
            structural.append("columns changed from [").append(String.join(",", oldRel.getColumns() != null ? oldRel.getColumns() : List.of()))
                    .append("] to [").append(String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of())).append("]; ");
        }
        
        // 정규화된 참조 테이블명 비교 (하지만 표시는 원본으로)
        if (!Objects.equals(oldNormalized.getRefTableKey(), newNormalized.getRefTableKey())) {
            structural.append("referencedTable changed from ").append(oldRel.getReferencedTable()).append(" to ").append(newRel.getReferencedTable()).append("; ");
        }
        
        // 정규화된 참조 컬럼 비교 (하지만 표시는 원본으로)
        if (!compareColumnKeys(oldNormalized.getReferencedColumnsKeys(), newNormalized.getReferencedColumnsKeys())) {
            structural.append("referencedColumns changed from [").append(String.join(",", oldRel.getReferencedColumns() != null ? oldRel.getReferencedColumns() : List.of()))
                    .append("] to [").append(String.join(",", newRel.getReferencedColumns() != null ? newRel.getReferencedColumns() : List.of())).append("]; ");
        }
        
        if (!Objects.equals(oldNormalized.getNormalizedConstraintName(), newNormalized.getNormalizedConstraintName())) {
            structural.append("constraintName changed from ").append(oldRel.getConstraintName()).append(" to ").append(newRel.getConstraintName()).append("; ");
        }
        
        if (!Objects.equals(oldRel.getOnDelete(), newRel.getOnDelete())) {
            structural.append("onDelete changed from ").append(oldRel.getOnDelete()).append(" to ").append(newRel.getOnDelete()).append("; ");
        }
        
        if (!Objects.equals(oldRel.getOnUpdate(), newRel.getOnUpdate())) {
            structural.append("onUpdate changed from ").append(oldRel.getOnUpdate()).append(" to ").append(newRel.getOnUpdate()).append("; ");
        }
        
        if (oldRel.isNoConstraint() != newRel.isNoConstraint()) {
            structural.append("noConstraint changed from ").append(oldRel.isNoConstraint()).append(" to ").append(newRel.isNoConstraint()).append("; ");
        }
        
        // mapsId 변경은 PK 승격에 영향을 주므로 구조적 변경
        if (oldRel.isMapsId() != newRel.isMapsId()) {
            structural.append("mapsId changed from ").append(oldRel.isMapsId()).append(" to ").append(newRel.isMapsId()).append("; ");
        }
        
        if (!Objects.equals(oldRel.getMapsIdBindings(), newRel.getMapsIdBindings())) {
            structural.append("mapsIdBindings changed from ").append(oldRel.getMapsIdBindings()).append(" to ").append(newRel.getMapsIdBindings()).append("; ");
        }
        
        if (!Objects.equals(oldRel.getMapsIdKeyPath(), newRel.getMapsIdKeyPath())) {
            structural.append("mapsIdKeyPath changed from ").append(oldRel.getMapsIdKeyPath()).append(" to ").append(newRel.getMapsIdKeyPath()).append("; ");
        }
        
        // === 비구조적 변경들 (런타임 의미만 변경) ===
        
        if (!Objects.equals(oldRel.getType(), newRel.getType())) {
            behavioral.append("type changed from ").append(oldRel.getType()).append(" to ").append(newRel.getType()).append("; ");
        }
        
        // EnumSet 기반 cascadeTypes 비교
        if (!compareCascadeTypes(oldRel.getCascadeTypes(), newRel.getCascadeTypes())) {
            behavioral.append("cascadeTypes changed from ").append(oldRel.getCascadeTypes()).append(" to ").append(newRel.getCascadeTypes()).append("; ");
        }
        
        if (oldRel.isOrphanRemoval() != newRel.isOrphanRemoval()) {
            behavioral.append("orphanRemoval changed from ").append(oldRel.isOrphanRemoval()).append(" to ").append(newRel.isOrphanRemoval()).append("; ");
        }
        
        if (!Objects.equals(oldRel.getFetchType(), newRel.getFetchType())) {
            behavioral.append("fetchType changed from ").append(oldRel.getFetchType()).append(" to ").append(newRel.getFetchType()).append("; ");
        }
        
        if (!Objects.equals(oldRel.getSourceAttributeName(), newRel.getSourceAttributeName())) {
            behavioral.append("sourceAttributeName changed from ").append(oldRel.getSourceAttributeName()).append(" to ").append(newRel.getSourceAttributeName()).append("; ");
        }
        
        // 태그를 포함한 최종 결과 생성
        StringBuilder result = new StringBuilder();
        
        if (structural.length() > 0) {
            // 마지막 "; " 제거
            if (structural.length() > 2) {
                structural.setLength(structural.length() - 2);
            }
            result.append("[STRUCTURAL] ").append(structural);
        }
        
        if (behavioral.length() > 0) {
            // 마지막 "; " 제거
            if (behavioral.length() > 2) {
                behavioral.setLength(behavioral.length() - 2);
            }
            if (result.length() > 0) {
                result.append(" | ");
            }
            result.append("[BEHAVIORAL] ").append(behavioral);
        }
        
        return result.toString();
    }

    private void analyzeRelationshipChanges(RelationshipModel oldRel, RelationshipModel newRel, DiffResult.ModifiedEntity modified) {
        String columnInfo = "[" + String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of()) + "]";
        
        // === 구조적 변경에 대한 경고들 ===
        
        // onDelete/onUpdate 변경 경고
        if (!Objects.equals(oldRel.getOnDelete(), newRel.getOnDelete())) {
            modified.getWarnings().add("Foreign key ON DELETE action changed for relationship on columns " + columnInfo +
                    " from " + oldRel.getOnDelete() + " to " + newRel.getOnDelete() + 
                    "; this affects referential integrity behavior and may impact existing data. " +
                    "Review dependent data before applying changes.");
        }
        
        if (!Objects.equals(oldRel.getOnUpdate(), newRel.getOnUpdate())) {
            modified.getWarnings().add("Foreign key ON UPDATE action changed for relationship on columns " + columnInfo +
                    " from " + oldRel.getOnUpdate() + " to " + newRel.getOnUpdate() + 
                    "; this affects referential integrity behavior and may impact data modification patterns.");
        }
        
        // noConstraint 토글 경고
        if (oldRel.isNoConstraint() != newRel.isNoConstraint()) {
            if (newRel.isNoConstraint()) {
                modified.getWarnings().add("Foreign key constraint disabled (NO_CONSTRAINT) for relationship on columns " + columnInfo + 
                        "; referential integrity will no longer be enforced at database level. " +
                        "Ensure application-level validation is properly implemented.");
            } else {
                modified.getWarnings().add("Foreign key constraint enabled for relationship on columns " + columnInfo + 
                        "; database will now enforce referential integrity. " +
                        "Validate existing data consistency before applying this change.");
            }
        }
        
        // mapsId 변경 경고 (PK 승격 관련)
        if (oldRel.isMapsId() != newRel.isMapsId()) {
            if (newRel.isMapsId()) {
                modified.getWarnings().add("@MapsId enabled for relationship on columns " + columnInfo + 
                        "; foreign key columns will now be part of the primary key. " +
                        "This is a significant structural change that affects entity identity and may require data migration.");
            } else {
                modified.getWarnings().add("@MapsId disabled for relationship on columns " + columnInfo + 
                        "; foreign key columns are no longer part of the primary key. " +
                        "This changes entity identity semantics and may require application logic updates.");
            }
        }
        
        if (!Objects.equals(oldRel.getMapsIdBindings(), newRel.getMapsIdBindings())) {
            modified.getWarnings().add("@MapsId column bindings changed for relationship on columns " + columnInfo + 
                    " from " + oldRel.getMapsIdBindings() + " to " + newRel.getMapsIdBindings() + 
                    "; this affects primary key composition and entity identity mapping.");
        }
        
        if (!Objects.equals(oldRel.getMapsIdKeyPath(), newRel.getMapsIdKeyPath())) {
            modified.getWarnings().add("@MapsId key path changed for relationship on columns " + columnInfo + 
                    " from '" + oldRel.getMapsIdKeyPath() + "' to '" + newRel.getMapsIdKeyPath() + "'" +
                    "; this changes how the foreign key maps to the primary key structure.");
        }
        
        // === 비구조적 변경에 대한 경고들 ===
        
        // EnumSet 기반 cascadeTypes 비교로 순서 변경 오탐 제거
        if (!compareCascadeTypes(oldRel.getCascadeTypes(), newRel.getCascadeTypes())) {
            EnumSet<CascadeType> oldSet = oldRel.getCascadeTypes() == null || oldRel.getCascadeTypes().isEmpty() 
                ? EnumSet.noneOf(CascadeType.class) 
                : EnumSet.copyOf(oldRel.getCascadeTypes());
            EnumSet<CascadeType> newSet = newRel.getCascadeTypes() == null || newRel.getCascadeTypes().isEmpty() 
                ? EnumSet.noneOf(CascadeType.class) 
                : EnumSet.copyOf(newRel.getCascadeTypes());
                
            EnumSet<CascadeType> added = EnumSet.copyOf(newSet);
            added.removeAll(oldSet);
            EnumSet<CascadeType> removed = EnumSet.copyOf(oldSet);
            removed.removeAll(newSet);
            
            StringBuilder warning = new StringBuilder("Persistence cascade options changed for relationship on columns " + columnInfo);
            if (!added.isEmpty()) {
                warning.append("; added: ").append(added);
            }
            if (!removed.isEmpty()) {
                warning.append("; removed: ").append(removed);
            }
            warning.append("; may affect automatic persistence operations and data consistency.");
            modified.getWarnings().add(warning.toString());
        }
        
        if (oldRel.isOrphanRemoval() != newRel.isOrphanRemoval()) {
            if (newRel.isOrphanRemoval()) {
                modified.getWarnings().add("Orphan removal enabled for relationship on columns " + columnInfo + 
                        "; entities will be automatically deleted when removed from the relationship collection. " +
                        "Ensure this behavior aligns with business logic.");
            } else {
                modified.getWarnings().add("Orphan removal disabled for relationship on columns " + columnInfo + 
                        "; entities will no longer be automatically deleted when removed from collections. " +
                        "Manual cleanup may be required to prevent orphaned records.");
            }
        }
        
        if (!Objects.equals(oldRel.getFetchType(), newRel.getFetchType())) {
            modified.getWarnings().add("Fetch strategy changed for relationship on columns " + columnInfo +
                    " from " + oldRel.getFetchType() + " to " + newRel.getFetchType() + 
                    "; this may impact query performance and N+1 query patterns. " +
                    "Review and test performance implications.");
        }
    }
}