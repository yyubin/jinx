package org.jinx.migration.differs;

import jakarta.persistence.CascadeType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RelationshipDiffer implements EntityComponentDiffer {
    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        newEntity.getRelationships().forEach(newRel -> {
            RelationshipModel oldRel = oldEntity.getRelationships().stream()
                    .filter(r -> Optional.ofNullable(r.getType()).equals(Optional.ofNullable(newRel.getType())) &&
                            Optional.ofNullable(r.getColumn()).equals(Optional.ofNullable(newRel.getColumn())))
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
                    .noneMatch(r -> Optional.ofNullable(r.getType()).equals(Optional.ofNullable(oldRel.getType())) &&
                            Optional.ofNullable(r.getColumn()).equals(Optional.ofNullable(oldRel.getColumn())))) {
                result.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.DROPPED)
                        .relationship(oldRel)
                        .build());
            }
        });
    }

    private boolean isRelationshipEqual(RelationshipModel oldRel, RelationshipModel newRel) {
        return Optional.ofNullable(oldRel.getType()).equals(Optional.ofNullable(newRel.getType())) &&
                Optional.ofNullable(oldRel.getColumn()).equals(Optional.ofNullable(newRel.getColumn())) &&
                Optional.ofNullable(oldRel.getReferencedTable()).equals(Optional.ofNullable(newRel.getReferencedTable())) &&
                Optional.ofNullable(oldRel.getReferencedColumn()).equals(Optional.ofNullable(newRel.getReferencedColumn())) &&
                oldRel.isMapsId() == newRel.isMapsId() &&
                Optional.ofNullable(oldRel.getCascadeTypes()).equals(Optional.ofNullable(newRel.getCascadeTypes())) &&
                oldRel.isOrphanRemoval() == newRel.isOrphanRemoval() &&
                oldRel.getFetchType() == newRel.getFetchType();
    }

    private String getRelationshipChangeDetail(RelationshipModel oldRel, RelationshipModel newRel) {
        StringBuilder detail = new StringBuilder();
        if (!Optional.ofNullable(oldRel.getType()).equals(Optional.ofNullable(newRel.getType()))) {
            detail.append("type changed from ").append(oldRel.getType()).append(" to ").append(newRel.getType()).append("; ");
        }
        if (!Optional.ofNullable(oldRel.getColumn()).equals(Optional.ofNullable(newRel.getColumn()))) {
            detail.append("column changed from ").append(oldRel.getColumn()).append(" to ").append(newRel.getColumn()).append("; ");
        }
        if (!Optional.ofNullable(oldRel.getReferencedTable()).equals(Optional.ofNullable(newRel.getReferencedTable()))) {
            detail.append("referencedTable changed from ").append(oldRel.getReferencedTable()).append(" to ").append(newRel.getReferencedTable()).append("; ");
        }
        if (!Optional.ofNullable(oldRel.getReferencedColumn()).equals(Optional.ofNullable(newRel.getReferencedColumn()))) {
            detail.append("referencedColumn changed from ").append(oldRel.getReferencedColumn()).append(" to ").append(newRel.getReferencedColumn()).append("; ");
        }
        if (oldRel.isMapsId() != newRel.isMapsId()) {
            detail.append("mapsId changed from ").append(oldRel.isMapsId()).append(" to ").append(newRel.isMapsId()).append("; ");
        }
        if (!Optional.ofNullable(oldRel.getCascadeTypes()).equals(Optional.ofNullable(newRel.getCascadeTypes()))) {
            detail.append("cascadeTypes changed from ").append(oldRel.getCascadeTypes()).append(" to ").append(newRel.getCascadeTypes()).append("; ");
        }
        if (oldRel.isOrphanRemoval() != newRel.isOrphanRemoval()) {
            detail.append("orphanRemoval changed from ").append(oldRel.isOrphanRemoval()).append(" to ").append(newRel.isOrphanRemoval()).append("; ");
        }
        if (oldRel.getFetchType() != newRel.getFetchType()) {
            detail.append("fetchType changed from ").append(oldRel.getFetchType()).append(" to ").append(newRel.getFetchType()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }

    private void analyzeRelationshipChanges(RelationshipModel oldRel, RelationshipModel newRel, DiffResult.ModifiedEntity modified) {
        if (!Optional.ofNullable(oldRel.getCascadeTypes()).equals(Optional.ofNullable(newRel.getCascadeTypes()))) {
            List<CascadeType> added = new ArrayList<>(newRel.getCascadeTypes() != null ? newRel.getCascadeTypes() : List.of());
            added.removeAll(oldRel.getCascadeTypes() != null ? oldRel.getCascadeTypes() : List.of());
            List<CascadeType> removed = new ArrayList<>(oldRel.getCascadeTypes() != null ? oldRel.getCascadeTypes() : List.of());
            removed.removeAll(newRel.getCascadeTypes() != null ? newRel.getCascadeTypes() : List.of());
            StringBuilder warning = new StringBuilder("Persistence cascade options changed for relationship on column " + newRel.getColumn());
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
                modified.getWarnings().add("Orphan removal enabled for relationship on column " + newRel.getColumn() +
                        "; may cause automatic deletion of related entities.");
            } else {
                modified.getWarnings().add("Orphan removal disabled for relationship on column " + newRel.getColumn() +
                        "; may affect data cleanup logic.");
            }
        }
        if (oldRel.getFetchType() != newRel.getFetchType()) {
            modified.getWarnings().add("Fetch strategy changed for relationship on column " + newRel.getColumn() +
                    " from " + oldRel.getFetchType() + " to " + newRel.getFetchType() +
                    "; may impact data retrieval performance.");
        }
    }
}