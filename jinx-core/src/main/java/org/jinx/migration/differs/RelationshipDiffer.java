package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class RelationshipDiffer implements EntityComponentDiffer {
    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        newEntity.getRelationships().forEach(newRel -> {
            RelationshipModel oldRel = oldEntity.getRelationships().stream()
                    // FIX: columns 리스트로 비교, type 제거로 유연성 유지
                    .filter(r -> Objects.equals(r.getColumns(), newRel.getColumns()))
                    .findFirst()
                    .orElse(null);
            if (oldRel == null) {
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.ADDED)
                        .relationship(newRel)
                        .build());
            } else if (!isRelationshipEqual(oldRel, newRel)) {
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.MODIFIED)
                        .relationship(newRel)
                        .oldRelationship(oldRel)
                        .changeDetail(getRelationshipChangeDetail(oldRel, newRel))
                        .build());
                analyzeRelationshipChanges(oldRel, newRel, result);
            }
        });

        oldEntity.getRelationships().forEach(oldRel -> {
            if (oldRel.getType() == null) return;
            if (newEntity.getRelationships().stream()
                    .noneMatch(r -> Objects.equals(r.getColumns(), oldRel.getColumns()))) {
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.DROPPED)
                        .relationship(oldRel)
                        .build());
            }
        });
    }

    private boolean isRelationshipEqual(RelationshipModel oldRel, RelationshipModel newRel) {
        return Objects.equals(oldRel.getType(), newRel.getType()) &&
                Objects.equals(oldRel.getColumns(), newRel.getColumns()) &&
                Objects.equals(oldRel.getReferencedTable(), newRel.getReferencedTable()) &&
                Objects.equals(oldRel.getReferencedColumns(), newRel.getReferencedColumns()) &&
                oldRel.isMapsId() == newRel.isMapsId() &&
                Objects.equals(oldRel.getCascadeTypes(), newRel.getCascadeTypes()) &&
                oldRel.isOrphanRemoval() == newRel.isOrphanRemoval() &&
                Objects.equals(oldRel.getFetchType(), newRel.getFetchType());
    }

    private String getRelationshipChangeDetail(RelationshipModel oldRel, RelationshipModel newRel) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldRel.getType(), newRel.getType())) {
            detail.append("type changed from ").append(oldRel.getType()).append(" to ").append(newRel.getType()).append("; ");
        }
        if (!Objects.equals(oldRel.getColumns(), newRel.getColumns())) {
            detail.append("columns changed from [").append(String.join(",", oldRel.getColumns() != null ? oldRel.getColumns() : List.of()))
                    .append("] to [").append(String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of())).append("]; ");
        }
        if (!Objects.equals(oldRel.getReferencedTable(), newRel.getReferencedTable())) {
            detail.append("referencedTable changed from ").append(oldRel.getReferencedTable()).append(" to ").append(newRel.getReferencedTable()).append("; ");
        }
        if (!Objects.equals(oldRel.getReferencedColumns(), newRel.getReferencedColumns())) {
            detail.append("referencedColumns changed from [").append(String.join(",", oldRel.getReferencedColumns() != null ? oldRel.getReferencedColumns() : List.of()))
                    .append("] to [").append(String.join(",", newRel.getReferencedColumns() != null ? newRel.getReferencedColumns() : List.of())).append("]; ");
        }
        if (oldRel.isMapsId() != newRel.isMapsId()) {
            detail.append("mapsId changed from ").append(oldRel.isMapsId()).append(" to ").append(newRel.isMapsId()).append("; ");
        }
        if (!Objects.equals(oldRel.getCascadeTypes(), newRel.getCascadeTypes())) {
            detail.append("cascadeTypes changed from ").append(oldRel.getCascadeTypes()).append(" to ").append(newRel.getCascadeTypes()).append("; ");
        }
        if (oldRel.isOrphanRemoval() != newRel.isOrphanRemoval()) {
            detail.append("orphanRemoval changed from ").append(oldRel.isOrphanRemoval()).append(" to ").append(newRel.isOrphanRemoval()).append("; ");
        }
        if (!Objects.equals(oldRel.getFetchType(), newRel.getFetchType())) {
            detail.append("fetchType changed from ").append(oldRel.getFetchType()).append(" to ").append(newRel.getFetchType()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }

    private void analyzeRelationshipChanges(RelationshipModel oldRel, RelationshipModel newRel, DiffResult.ModifiedEntity modified) {
        if (!Objects.equals(oldRel.getCascadeTypes(), newRel.getCascadeTypes())) {
            List<CascadeType> added = new ArrayList<>(newRel.getCascadeTypes() != null ? newRel.getCascadeTypes() : List.of());
            added.removeAll(oldRel.getCascadeTypes() != null ? oldRel.getCascadeTypes() : List.of());
            List<CascadeType> removed = new ArrayList<>(oldRel.getCascadeTypes() != null ? oldRel.getCascadeTypes() : List.of());
            removed.removeAll(newRel.getCascadeTypes() != null ? newRel.getCascadeTypes() : List.of());
            StringBuilder warning = new StringBuilder("Persistence cascade options changed for relationship on columns [" +
                    String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of()) + "]");
            if (!added.isEmpty()) {
                warning.append("; added: ").append(added);
            }
            if (!removed.isEmpty()) {
                warning.append("; removed: ").append(removed);
            }
            warning.append("; may affect data consistency.");
            modified.getWarnings().add(warning.toString());
        }
        if (oldRel.isOrphanRemoval() != newRel.isOrphanRemoval()) {
            if (newRel.isOrphanRemoval()) {
                modified.getWarnings().add("Orphan removal enabled for relationship on columns [" +
                        String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of()) +
                        "]; may cause automatic deletion of related entities.");
            } else {
                modified.getWarnings().add("Orphan removal disabled for relationship on columns [" +
                        String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of()) +
                        "]; may affect data cleanup logic.");
            }
        }
        if (!Objects.equals(oldRel.getFetchType(), newRel.getFetchType())) {
            modified.getWarnings().add("Fetch strategy changed for relationship on columns [" +
                    String.join(",", newRel.getColumns() != null ? newRel.getColumns() : List.of()) +
                    "] from " + oldRel.getFetchType() + " to " + newRel.getFetchType() +
                    "; may impact data retrieval performance.");
        }
    }
}