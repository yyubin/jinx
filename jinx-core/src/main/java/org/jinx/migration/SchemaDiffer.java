package org.jinx.migration;

import lombok.Builder;
import org.jinx.model.*;
import java.util.*;

public class SchemaDiffer {

    public DiffResult diff(SchemaModel oldSchema, SchemaModel newSchema) {
        DiffResult result = DiffResult.builder().build();

        // 1. Added Tables
        newSchema.getEntities().forEach((name, newEntity) -> {
            if (!oldSchema.getEntities().containsKey(name)) {
                result.getAddedTables().add(newEntity);
            }
        });

        // 2. Dropped Tables
        oldSchema.getEntities().forEach((name, oldEntity) -> {
            if (!newSchema.getEntities().containsKey(name)) {
                result.getDroppedTables().add(oldEntity);
            }
        });

        // 3. Modified Tables
        newSchema.getEntities().forEach((name, newEntity) -> {
            if (oldSchema.getEntities().containsKey(name)) {
                EntityModel oldEntity = oldSchema.getEntities().get(name);
                DiffResult.ModifiedEntity modified = compareEntities(oldEntity, newEntity);
                if (!modified.getColumnDiffs().isEmpty() || !modified.getIndexDiffs().isEmpty() ||
                        !modified.getConstraintDiffs().isEmpty() || !modified.getRelationshipDiffs().isEmpty()) {
                    result.getModifiedTables().add(modified);
                }
            }
        });

        return result;
    }

    private DiffResult.ModifiedEntity compareEntities(EntityModel oldEntity, EntityModel newEntity) {
        DiffResult.ModifiedEntity modified = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        // Compare Columns
        newEntity.getColumns().forEach((name, newColumn) -> {
            if (!oldEntity.getColumns().containsKey(name)) {
                DiffResult.ColumnDiff diff = DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.ADDED)
                        .column(newColumn)
                        .build();
                modified.getColumnDiffs().add(diff);
            } else {
                ColumnModel oldColumn = oldEntity.getColumns().get(name);
                if (!isColumnEqual(oldColumn, newColumn)) {
                    DiffResult.ColumnDiff diff = DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn)
                            .oldColumn(oldColumn)
                            .changeDetail(getColumnChangeDetail(oldColumn, newColumn))
                            .build();
                    modified.getColumnDiffs().add(diff);
                }
            }
        });
        oldEntity.getColumns().forEach((name, oldColumn) -> {
            if (!newEntity.getColumns().containsKey(name)) {
                DiffResult.ColumnDiff diff = DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.DROPPED)
                        .column(oldColumn)
                        .build();
                modified.getColumnDiffs().add(diff);
            }
        });

        // Compare Indexes
        newEntity.getIndexes().forEach((name, newIndex) -> {
            if (!oldEntity.getIndexes().containsKey(name)) {
                DiffResult.IndexDiff diff = DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.ADDED)
                        .index(newIndex)
                        .build();
                modified.getIndexDiffs().add(diff);
            } else {
                IndexModel oldIndex = oldEntity.getIndexes().get(name);
                if (!isIndexEqual(oldIndex, newIndex)) {
                    DiffResult.IndexDiff diff = DiffResult.IndexDiff.builder()
                            .type(DiffResult.IndexDiff.Type.MODIFIED)
                            .index(newIndex)
                            .changeDetail(getIndexChangeDetail(oldIndex, newIndex))
                            .build();
                    modified.getIndexDiffs().add(diff);
                }
            }
        });
        oldEntity.getIndexes().forEach((name, oldIndex) -> {
            if (!newEntity.getIndexes().containsKey(name)) {
                DiffResult.IndexDiff diff = DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.DROPPED)
                        .index(oldIndex)
                        .build();
                modified.getIndexDiffs().add(diff);
            }
        });

        // Compare Constraints
        newEntity.getConstraints().forEach(newConstraint -> {
            ConstraintModel oldConstraint = oldEntity.getConstraints().stream()
                    .filter(c -> c.getName().equals(newConstraint.getName()))
                    .findFirst().orElse(null);
            if (oldConstraint == null) {
                DiffResult.ConstraintDiff diff = DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.ADDED)
                        .constraint(newConstraint)
                        .build();
                modified.getConstraintDiffs().add(diff);
            } else if (!isConstraintEqual(oldConstraint, newConstraint)) {
                DiffResult.ConstraintDiff diff = DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.MODIFIED)
                        .constraint(newConstraint)
                        .changeDetail(getConstraintChangeDetail(oldConstraint, newConstraint))
                        .build();
                modified.getConstraintDiffs().add(diff);
            }
        });
        oldEntity.getConstraints().forEach(oldConstraint -> {
            if (newEntity.getConstraints().stream().noneMatch(c -> c.getName().equals(oldConstraint.getName()))) {
                DiffResult.ConstraintDiff diff = DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.DROPPED)
                        .constraint(oldConstraint)
                        .build();
                modified.getConstraintDiffs().add(diff);
            }
        });

        // Compare Relationships
        newEntity.getRelationships().forEach(newRel -> {
            RelationshipModel oldRel = oldEntity.getRelationships().stream()
                    .filter(r -> r.getType().equals(newRel.getType()) && r.getColumn().equals(newRel.getColumn()))
                    .findFirst().orElse(null);
            if (oldRel == null) {
                DiffResult.RelationshipDiff diff = DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.ADDED)
                        .relationship(newRel)
                        .build();
                modified.getRelationshipDiffs().add(diff);
            } else if (!isRelationshipEqual(oldRel, newRel)) {
                DiffResult.RelationshipDiff diff = DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.MODIFIED)
                        .relationship(newRel)
                        .changeDetail(getRelationshipChangeDetail(oldRel, newRel))
                        .build();
                modified.getRelationshipDiffs().add(diff);
            }
        });
        oldEntity.getRelationships().forEach(oldRel -> {
            if (newEntity.getRelationships().stream().noneMatch(r -> r.getType().equals(oldRel.getType()) && r.getColumn().equals(oldRel.getColumn()))) {
                DiffResult.RelationshipDiff diff = DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.DROPPED)
                        .relationship(oldRel)
                        .build();
                modified.getRelationshipDiffs().add(diff);
            }
        });

        return modified;
    }

    private boolean isColumnEqual(ColumnModel oldCol, ColumnModel newCol) {
        return Objects.equals(oldCol.getSqlType(), newCol.getSqlType()) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength();
    }

    private String getColumnChangeDetail(ColumnModel oldCol, ColumnModel newCol) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldCol.getSqlType(), newCol.getSqlType())) {
            detail.append("sqlType changed from ").append(oldCol.getSqlType()).append(" to ").append(newCol.getSqlType()).append("; ");
        }
        if (oldCol.isNullable() != newCol.isNullable()) {
            detail.append("isNullable changed from ").append(oldCol.isNullable()).append(" to ").append(newCol.isNullable()).append("; ");
        }
        if (oldCol.isUnique() != newCol.isUnique()) {
            detail.append("isUnique changed from ").append(oldCol.isUnique()).append(" to ").append(newCol.isUnique()).append("; ");
        }
        if (oldCol.getLength() != newCol.getLength()) {
            detail.append("length changed from ").append(oldCol.getLength()).append(" to ").append(newCol.getLength()).append("; ");
        }
        return detail.toString();
    }

    private boolean isIndexEqual(IndexModel oldIndex, IndexModel newIndex) {
        return oldIndex.isUnique() == newIndex.isUnique() &&
                oldIndex.getColumnNames().equals(newIndex.getColumnNames());
    }

    private String getIndexChangeDetail(IndexModel oldIndex, IndexModel newIndex) {
        StringBuilder detail = new StringBuilder();
        if (oldIndex.isUnique() != newIndex.isUnique()) {
            detail.append("isUnique changed from ").append(oldIndex.isUnique()).append(" to ").append(newIndex.isUnique()).append("; ");
        }
        if (!oldIndex.getColumnNames().equals(newIndex.getColumnNames())) {
            detail.append("columns changed from ").append(oldIndex.getColumnNames()).append(" to ").append(newIndex.getColumnNames()).append("; ");
        }
        return detail.toString();
    }

    private boolean isConstraintEqual(ConstraintModel oldCons, ConstraintModel newCons) {
        return Objects.equals(oldCons.getType(), newCons.getType()) &&
                Objects.equals(oldCons.getColumn(), newCons.getColumn()) &&
                Objects.equals(oldCons.getReferencedTable(), newCons.getReferencedTable()) &&
                Objects.equals(oldCons.getReferencedColumn(), newCons.getReferencedColumn()) &&
                oldCons.getOnDelete() == newCons.getOnDelete() &&
                oldCons.getOnUpdate() == newCons.getOnUpdate();
    }

    private String getConstraintChangeDetail(ConstraintModel oldCons, ConstraintModel newCons) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldCons.getType(), newCons.getType())) {
            detail.append("type changed from ").append(oldCons.getType()).append(" to ").append(newCons.getType()).append("; ");
        }
        if (!Objects.equals(oldCons.getColumn(), newCons.getColumn())) {
            detail.append("column changed from ").append(oldCons.getColumn()).append(" to ").append(newCons.getColumn()).append("; ");
        }
        if (!Objects.equals(oldCons.getReferencedTable(), newCons.getReferencedTable())) {
            detail.append("referencedTable changed from ").append(oldCons.getReferencedTable()).append(" to ").append(newCons.getReferencedTable()).append("; ");
        }
        if (!Objects.equals(oldCons.getReferencedColumn(), newCons.getReferencedColumn())) {
            detail.append("referencedColumn changed from ").append(oldCons.getReferencedColumn()).append(" to ").append(newCons.getReferencedColumn()).append("; ");
        }
        if (oldCons.getOnDelete() != newCons.getOnDelete()) {
            detail.append("onDelete changed from ").append(oldCons.getOnDelete()).append(" to ").append(newCons.getOnDelete()).append("; ");
        }
        if (oldCons.getOnUpdate() != newCons.getOnUpdate()) {
            detail.append("onUpdate changed from ").append(oldCons.getOnUpdate()).append(" to ").append(newCons.getOnUpdate()).append("; ");
        }
        return detail.toString();
    }

    private boolean isRelationshipEqual(RelationshipModel oldRel, RelationshipModel newRel) {
        return Objects.equals(oldRel.getType(), newRel.getType()) &&
                Objects.equals(oldRel.getColumn(), newRel.getColumn()) &&
                Objects.equals(oldRel.getReferencedTable(), newRel.getReferencedTable()) &&
                Objects.equals(oldRel.getReferencedColumn(), newRel.getReferencedColumn()) &&
                oldRel.isMapsId() == newRel.isMapsId();
    }

    private String getRelationshipChangeDetail(RelationshipModel oldRel, RelationshipModel newRel) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldRel.getType(), newRel.getType())) {
            detail.append("type changed from ").append(oldRel.getType()).append(" to ").append(newRel.getType()).append("; ");
        }
        if (!Objects.equals(oldRel.getColumn(), newRel.getColumn())) {
            detail.append("column changed from ").append(oldRel.getColumn()).append(" to ").append(newRel.getColumn()).append("; ");
        }
        if (!Objects.equals(oldRel.getReferencedTable(), newRel.getReferencedTable())) {
            detail.append("referencedTable changed from ").append(oldRel.getReferencedTable()).append(" to ").append(newRel.getReferencedTable()).append("; ");
        }
        if (!Objects.equals(oldRel.getReferencedColumn(), newRel.getReferencedColumn())) {
            detail.append("referencedColumn changed from ").append(oldRel.getReferencedColumn()).append(" to ").append(newRel.getReferencedColumn()).append("; ");
        }
        if (oldRel.isMapsId() != newRel.isMapsId()) {
            detail.append("mapsId changed from ").append(oldRel.isMapsId()).append(" to ").append(newRel.isMapsId()).append("; ");
        }
        return detail.toString();
    }
}