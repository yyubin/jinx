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
                // Propagate entity-level warnings to schema-level
                result.getWarnings().addAll(modified.getWarnings());
            }
        });

        // 4. Compare Sequences
        compareSequences(oldSchema, newSchema, result);

        // 5. Compare Table Generators
        compareTableGenerators(oldSchema, newSchema, result);

        return result;
    }

    private void compareSequences(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        newSchema.getSequences().forEach((name, seq) -> {
            if (!oldSchema.getSequences().containsKey(name)) {
                result.getSequenceDiffs().add(DiffResult.SequenceDiff.added(seq));
            } else if (!isSequenceEqual(oldSchema.getSequences().get(name), seq)) {
                result.getSequenceDiffs().add(DiffResult.SequenceDiff.modified(
                        oldSchema.getSequences().get(name), seq));
            }
        });
        oldSchema.getSequences().forEach((name, seq) -> {
            if (!newSchema.getSequences().containsKey(name)) {
                result.getSequenceDiffs().add(DiffResult.SequenceDiff.dropped(seq));
            }
        });
    }

    private void compareTableGenerators(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        newSchema.getTableGenerators().forEach((name, tg) -> {
            if (!oldSchema.getTableGenerators().containsKey(name)) {
                result.getTableGeneratorDiffs().add(DiffResult.TableGeneratorDiff.added(tg));
            } else if (!isTableGeneratorEqual(oldSchema.getTableGenerators().get(name), tg)) {
                result.getTableGeneratorDiffs().add(DiffResult.TableGeneratorDiff.modified(
                        oldSchema.getTableGenerators().get(name), tg));
            }
        });
        oldSchema.getTableGenerators().forEach((name, tg) -> {
            if (!newSchema.getTableGenerators().containsKey(name)) {
                result.getTableGeneratorDiffs().add(DiffResult.TableGeneratorDiff.dropped(tg));
            }
        });
    }

    private DiffResult.ModifiedEntity compareEntities(EntityModel oldEntity, EntityModel newEntity) {
        DiffResult.ModifiedEntity modified = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        // Check for inheritance strategy changes
        if (!Objects.equals(oldEntity.getInheritance(), newEntity.getInheritance())) {
            String warning = "Inheritance strategy changed from " + oldEntity.getInheritance() +
                    " to " + newEntity.getInheritance() + " for entity " + newEntity.getEntityName() +
                    "; manual migration required as this cannot be automatically handled.";
            modified.getWarnings().add(warning);
        }

        // Compare Columns
        Map<String, ColumnModel> oldColumns = new HashMap<>(oldEntity.getColumns());
        Map<String, ColumnModel> newColumns = new HashMap<>(newEntity.getColumns());

        Set<String> processedNewColumns = new HashSet<>();
        for (Map.Entry<String, ColumnModel> newEntry : newColumns.entrySet()) {
            Optional<Map.Entry<String, ColumnModel>> renamedMatch = oldColumns.entrySet().stream()
                    .filter(oldEntry -> !newColumns.containsKey(oldEntry.getKey()) && isColumnAttributesEqual(oldEntry.getValue(), newEntry.getValue()))
                    .findFirst();

            if (renamedMatch.isPresent()) {
                String oldName = renamedMatch.get().getKey();
                ColumnModel oldColumn = renamedMatch.get().getValue();
                ColumnModel newColumn = newEntry.getValue();
                modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.RENAMED)
                        .column(newColumn)
                        .oldColumn(oldColumn)
                        .changeDetail("columnName changed from " + oldName + " to " + newEntry.getKey())
                        .build());
                // Check for additional modifications (e.g., length, precision, scale)
                if (!isColumnEqualExceptName(oldColumn, newColumn)) {
                    modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn)
                            .oldColumn(oldColumn)
                            .changeDetail(getColumnChangeDetail(oldColumn, newColumn))
                            .build());
                }
                oldColumns.remove(oldName);
                processedNewColumns.add(newEntry.getKey());
            }
        }

        for (Map.Entry<String, ColumnModel> newEntry : newColumns.entrySet()) {
            if (processedNewColumns.contains(newEntry.getKey())) continue;

            String newName = newEntry.getKey();
            ColumnModel newColumn = newEntry.getValue();

            if (!oldColumns.containsKey(newName)) {
                modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.ADDED).column(newColumn).build());
            } else {
                ColumnModel oldColumn = oldColumns.get(newName);
                if (isEnumMappingChanged(oldColumn, newColumn)) {
                    modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn).oldColumn(oldColumn)
                            .changeDetail("Enum mapping changed " + (oldColumn.isEnumStringMapping() ? "STRING -> ORDINAL" : "ORDINAL -> STRING"))
                            .build());
                } else if (!isColumnEqual(oldColumn, newColumn)) {
                    modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn).oldColumn(oldColumn)
                            .changeDetail(getColumnChangeDetail(oldColumn, newColumn))
                            .build());
                }
                oldColumns.remove(newName);
            }
        }

        oldColumns.forEach((name, oldColumn) -> modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                .type(DiffResult.ColumnDiff.Type.DROPPED).column(oldColumn).build()));

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

    private boolean isEnum(ColumnModel col) {
        return col.getEnumValues() != null && col.getEnumValues().length > 0;
    }

    private boolean isEnumMappingChanged(ColumnModel oldCol, ColumnModel newCol) {
        return isEnum(oldCol) && isEnum(newCol) && oldCol.isEnumStringMapping() != newCol.isEnumStringMapping();
    }

    private boolean isColumnEqual(ColumnModel oldCol, ColumnModel newCol) {
        return Objects.equals(oldCol.getColumnName(), newCol.getColumnName()) &&
                Objects.equals(oldCol.getJavaType(), newCol.getJavaType()) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                Objects.equals(oldCol.getDefaultValue(), newCol.getDefaultValue()) &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                Objects.equals(oldCol.getSequenceName(), newCol.getSequenceName()) &&
                Objects.equals(oldCol.getTableGeneratorName(), newCol.getTableGeneratorName()) &&
                oldCol.getIdentityStartValue() == newCol.getIdentityStartValue() &&
                oldCol.getIdentityIncrement() == newCol.getIdentityIncrement() &&
                oldCol.getIdentityCache() == newCol.getIdentityCache() &&
                oldCol.getIdentityMinValue() == newCol.getIdentityMinValue() &&
                oldCol.getIdentityMaxValue() == newCol.getIdentityMaxValue() &&
                Arrays.equals(oldCol.getIdentityOptions(), newCol.getIdentityOptions()) &&
                oldCol.isManualPrimaryKey() == newCol.isManualPrimaryKey() &&
                oldCol.isEnumStringMapping() == newCol.isEnumStringMapping() &&
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues());
    }

    private boolean isColumnEqualExceptName(ColumnModel oldCol, ColumnModel newCol) {
        return Objects.equals(oldCol.getJavaType(), newCol.getJavaType()) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                Objects.equals(oldCol.getDefaultValue(), newCol.getDefaultValue()) &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                Objects.equals(oldCol.getSequenceName(), newCol.getSequenceName()) &&
                Objects.equals(oldCol.getTableGeneratorName(), newCol.getTableGeneratorName()) &&
                oldCol.getIdentityStartValue() == newCol.getIdentityStartValue() &&
                oldCol.getIdentityIncrement() == newCol.getIdentityIncrement() &&
                oldCol.getIdentityCache() == newCol.getIdentityCache() &&
                oldCol.getIdentityMinValue() == newCol.getIdentityMinValue() &&
                oldCol.getIdentityMaxValue() == newCol.getIdentityMaxValue() &&
                Arrays.equals(oldCol.getIdentityOptions(), newCol.getIdentityOptions()) &&
                oldCol.isManualPrimaryKey() == newCol.isManualPrimaryKey() &&
                oldCol.isEnumStringMapping() == newCol.isEnumStringMapping() &&
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues());
    }

    private boolean isColumnAttributesEqual(ColumnModel oldCol, ColumnModel newCol) {
        return Objects.equals(oldCol.getJavaType(), newCol.getJavaType()) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                Objects.equals(oldCol.getSequenceName(), newCol.getSequenceName()) &&
                Objects.equals(oldCol.getTableGeneratorName(), newCol.getTableGeneratorName()) &&
                oldCol.isEnumStringMapping() == newCol.isEnumStringMapping() &&
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues());
    }

    private String getColumnChangeDetail(ColumnModel oldCol, ColumnModel newCol) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldCol.getJavaType(), newCol.getJavaType())) {
            detail.append("javaType changed from ").append(oldCol.getJavaType()).append(" to ").append(newCol.getJavaType()).append("; ");
        }
        if (oldCol.isPrimaryKey() != newCol.isPrimaryKey()) {
            detail.append("isPrimaryKey changed from ").append(oldCol.isPrimaryKey()).append(" to ").append(newCol.isPrimaryKey()).append("; ");
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
        if (oldCol.getPrecision() != newCol.getPrecision()) {
            detail.append("precision changed from ").append(oldCol.getPrecision()).append(" to ").append(newCol.getPrecision()).append("; ");
        }
        if (oldCol.getScale() != newCol.getScale()) {
            detail.append("scale changed from ").append(oldCol.getScale()).append(" to ").append(newCol.getScale()).append("; ");
        }
        if (!Objects.equals(oldCol.getDefaultValue(), newCol.getDefaultValue())) {
            detail.append("defaultValue changed from ").append(oldCol.getDefaultValue()).append(" to ").append(newCol.getDefaultValue()).append("; ");
        }
        if (oldCol.getGenerationStrategy() != newCol.getGenerationStrategy()) {
            detail.append("generationStrategy changed from ").append(oldCol.getGenerationStrategy()).append(" to ").append(newCol.getGenerationStrategy()).append("; ");
        }
        if (!Objects.equals(oldCol.getSequenceName(), newCol.getSequenceName())) {
            detail.append("sequenceName changed from ").append(oldCol.getSequenceName()).append(" to ").append(newCol.getSequenceName()).append("; ");
        }
        if (!Objects.equals(oldCol.getTableGeneratorName(), newCol.getTableGeneratorName())) {
            detail.append("tableGeneratorName changed from ").append(oldCol.getTableGeneratorName()).append(" to ").append(newCol.getTableGeneratorName()).append("; ");
        }
        if (oldCol.getIdentityStartValue() != newCol.getIdentityStartValue()) {
            detail.append("identityStartValue changed from ").append(oldCol.getIdentityStartValue()).append(" to ").append(newCol.getIdentityStartValue()).append("; ");
        }
        if (oldCol.getIdentityIncrement() != newCol.getIdentityIncrement()) {
            detail.append("identityIncrement changed from ").append(oldCol.getIdentityIncrement()).append(" to ").append(newCol.getIdentityIncrement()).append("; ");
        }
        if (oldCol.getIdentityCache() != newCol.getIdentityCache()) {
            detail.append("identityCache changed from ").append(oldCol.getIdentityCache()).append(" to ").append(newCol.getIdentityCache()).append("; ");
        }
        if (oldCol.getIdentityMinValue() != newCol.getIdentityMinValue()) {
            detail.append("identityMinValue changed from ").append(oldCol.getIdentityMinValue()).append(" to ").append(newCol.getIdentityMinValue()).append("; ");
        }
        if (oldCol.getIdentityMaxValue() != newCol.getIdentityMaxValue()) {
            detail.append("identityMaxValue changed from ").append(oldCol.getIdentityMaxValue()).append(" to ").append(newCol.getIdentityMaxValue()).append("; ");
        }
        if (!Arrays.equals(oldCol.getIdentityOptions(), newCol.getIdentityOptions())) {
            detail.append("identityOptions changed from ").append(Arrays.toString(oldCol.getIdentityOptions())).append(" to ").append(Arrays.toString(newCol.getIdentityOptions())).append("; ");
        }
        if (!Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues())) {
            detail.append("enumValues changed; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2); // Remove trailing "; "
        }
        return detail.toString();
    }

    private boolean isSequenceEqual(SequenceModel oldSeq, SequenceModel newSeq) {
        return oldSeq.getInitialValue() == newSeq.getInitialValue() &&
                oldSeq.getAllocationSize() == newSeq.getAllocationSize() &&
                oldSeq.getCache() == newSeq.getCache() &&
                oldSeq.getMinValue() == newSeq.getMinValue() &&
                oldSeq.getMaxValue() == newSeq.getMaxValue();
    }

    private boolean isTableGeneratorEqual(TableGeneratorModel oldTg, TableGeneratorModel newTg) {
        return Objects.equals(oldTg.getTable(), newTg.getTable()) &&
                Objects.equals(oldTg.getSchema(), newTg.getSchema()) &&
                Objects.equals(oldTg.getCatalog(), newTg.getCatalog()) &&
                Objects.equals(oldTg.getPkColumnName(), newTg.getPkColumnName()) &&
                Objects.equals(oldTg.getValueColumnName(), newTg.getValueColumnName()) &&
                Objects.equals(oldTg.getPkColumnValue(), newTg.getPkColumnValue()) &&
                oldTg.getInitialValue() == newTg.getInitialValue() &&
                oldTg.getAllocationSize() == newTg.getAllocationSize();
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
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
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
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
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
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }
}