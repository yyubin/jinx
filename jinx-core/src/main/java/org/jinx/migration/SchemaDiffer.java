package org.jinx.migration;

import lombok.Builder;
import lombok.Getter;
import org.jinx.model.*;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;

import java.util.*;
import java.util.stream.Collectors;

public class SchemaDiffer {

    public DiffResult diff(SchemaModel oldSchema, SchemaModel newSchema) {
        DiffResult result = DiffResult.builder().build();

        // 1. Detect Renamed Tables
        detectRenamedTables(oldSchema, newSchema, result);

        // 2. Added Tables
        newSchema.getEntities().forEach((name, newEntity) -> {
            if (!oldSchema.getEntities().containsKey(name) &&
                    !result.getRenamedTables().stream().anyMatch(rt -> rt.getNewEntity().getEntityName().equals(name))) {
                result.getAddedTables().add(newEntity);
            }
        });

        // 3. Dropped Tables
        oldSchema.getEntities().forEach((name, oldEntity) -> {
            if (!newSchema.getEntities().containsKey(name) &&
                    !result.getRenamedTables().stream().anyMatch(rt -> rt.getOldEntity().getEntityName().equals(name))) {
                result.getDroppedTables().add(oldEntity);
            }
        });

        // 4. Modified Tables
        newSchema.getEntities().forEach((name, newEntity) -> {
            if (oldSchema.getEntities().containsKey(name)) {
                EntityModel oldEntity = oldSchema.getEntities().get(name);
                DiffResult.ModifiedEntity modified = compareEntities(oldEntity, newEntity);
                if (!modified.getColumnDiffs().isEmpty() || !modified.getIndexDiffs().isEmpty() ||
                        !modified.getConstraintDiffs().isEmpty() || !modified.getRelationshipDiffs().isEmpty() ||
                        !modified.getWarnings().isEmpty()) {
                    result.getModifiedTables().add(modified);
                }
                result.getWarnings().addAll(modified.getWarnings());
            }
        });

        // 5. Compare Sequences
        compareSequences(oldSchema, newSchema, result);

        // 6. Compare Table Generators
        compareTableGenerators(oldSchema, newSchema, result);

        return result;
    }

    private void detectRenamedTables(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        // Precompute PK column names and column hashes
        Map<String, Set<String>> oldPkColumnNames = oldSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .filter(ColumnModel::isPrimaryKey)
                                .map(ColumnModel::getColumnName)
                                .collect(Collectors.toSet())
                ));

        Map<String, Set<Long>> oldColumnHashes = oldSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .map(ColumnModel::getAttributeHash)
                                .collect(Collectors.toSet())
                ));

        Map<String, Set<String>> newPkColumnNames = newSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .filter(ColumnModel::isPrimaryKey)
                                .map(ColumnModel::getColumnName)
                                .collect(Collectors.toSet())
                ));

        Map<String, Set<Long>> newColumnHashes = newSchema.getEntities().values().stream()
                .collect(Collectors.toMap(
                        EntityModel::getEntityName,
                        e -> e.getColumns().values().stream()
                                .map(ColumnModel::getAttributeHash)
                                .collect(Collectors.toSet())
                ));

        // Iterate over dropped tables
        for (String oldEntityName : oldSchema.getEntities().keySet()) {
            if (!newSchema.getEntities().containsKey(oldEntityName)) {
                EntityModel oldEntity = oldSchema.getEntities().get(oldEntityName);
                Set<String> oldPkNames = oldPkColumnNames.get(oldEntityName);
                Set<Long> oldHashes = oldColumnHashes.get(oldEntityName);

                // Find matching added tables by PK column names
                for (String newEntityName : newSchema.getEntities().keySet()) {
                    if (!oldSchema.getEntities().containsKey(newEntityName)) {
                        EntityModel newEntity = newSchema.getEntities().get(newEntityName);
                        Set<String> newPkNames = newPkColumnNames.get(newEntityName);
                        Set<Long> newHashes = newColumnHashes.get(newEntityName);

                        // 1st filter: PK column names must match exactly
                        if (oldPkNames.equals(newPkNames)) {
                            // 2nd filter: All column hashes must match 100%
                            if (oldHashes.equals(newHashes)) {
                                result.getRenamedTables().add(DiffResult.RenamedTable.builder()
                                        .oldEntity(oldEntity)
                                        .newEntity(newEntity)
                                        .changeDetail("Table renamed from " + oldEntity.getTableName() + " to " + newEntity.getTableName())
                                        .build());
                            }
                        }
                    }
                }
            }
        }
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

        // Check for schema/catalog changes
        if (!Objects.equals(oldEntity.getSchema(), newEntity.getSchema())) {
            modified.getWarnings().add("Schema changed from " + oldEntity.getSchema() + " to " + newEntity.getSchema() +
                    " for entity " + newEntity.getEntityName());
        }
        if (!Objects.equals(oldEntity.getCatalog(), newEntity.getCatalog())) {
            modified.getWarnings().add("Catalog changed from " + oldEntity.getCatalog() + " to " + newEntity.getCatalog() +
                    " for entity " + newEntity.getEntityName());
        }

        // Check for inheritance strategy changes
        if (!Objects.equals(oldEntity.getInheritance(), newEntity.getInheritance())) {
            modified.getWarnings().add("Inheritance strategy changed from " + oldEntity.getInheritance() +
                    " to " + newEntity.getInheritance() + " for entity " + newEntity.getEntityName() +
                    "; manual migration required.");
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
                        .changeDetail("Column renamed from " + oldName + " to " + newEntry.getKey())
                        .build());
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
                    analyzeEnumChanges(oldColumn, newColumn, modified);
                } else if (!isColumnEqual(oldColumn, newColumn)) {
                    modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn).oldColumn(oldColumn)
                            .changeDetail(getColumnChangeDetail(oldColumn, newColumn))
                            .build());
                    analyzeColumnChanges(oldColumn, newColumn, modified);
                }
                oldColumns.remove(newName);
            }
        }

        oldColumns.forEach((name, oldColumn) -> modified.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                .type(DiffResult.ColumnDiff.Type.DROPPED).column(oldColumn).build()));

        // Compare Indexes
        newEntity.getIndexes().forEach((name, newIndex) -> {
            if (!oldEntity.getIndexes().containsKey(name)) {
                modified.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.ADDED)
                        .index(newIndex)
                        .build());
            } else {
                IndexModel oldIndex = oldEntity.getIndexes().get(name);
                if (!isIndexEqual(oldIndex, newIndex)) {
                    modified.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                            .type(DiffResult.IndexDiff.Type.MODIFIED)
                            .index(newIndex)
                            .changeDetail(getIndexChangeDetail(oldIndex, newIndex))
                            .build());
                }
            }
        });
        oldEntity.getIndexes().forEach((name, oldIndex) -> {
            if (!newEntity.getIndexes().containsKey(name)) {
                modified.getIndexDiffs().add(DiffResult.IndexDiff.builder()
                        .type(DiffResult.IndexDiff.Type.DROPPED)
                        .index(oldIndex)
                        .build());
            }
        });

        // Compare Constraints
        newEntity.getConstraints().forEach(newConstraint -> {
            ConstraintModel oldConstraint = oldEntity.getConstraints().stream()
                    .filter(c -> c.getName().equals(newConstraint.getName()))
                    .findFirst().orElse(null);
            if (oldConstraint == null) {
                modified.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.ADDED)
                        .constraint(newConstraint)
                        .build());
            } else if (!isConstraintEqual(oldConstraint, newConstraint)) {
                modified.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.MODIFIED)
                        .constraint(newConstraint)
                        .changeDetail(getConstraintChangeDetail(oldConstraint, newConstraint))
                        .build());
            }
        });
        oldEntity.getConstraints().forEach(oldConstraint -> {
            if (newEntity.getConstraints().stream().noneMatch(c -> c.getName().equals(oldConstraint.getName()))) {
                modified.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.DROPPED)
                        .constraint(oldConstraint)
                        .build());
            }
        });

        // Compare Relationships
        newEntity.getRelationships().forEach(newRel -> {
            RelationshipModel oldRel = oldEntity.getRelationships().stream()
                    .filter(r -> r.getType().equals(newRel.getType()) && r.getColumn().equals(newRel.getColumn()))
                    .findFirst().orElse(null);
            if (oldRel == null) {
                modified.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.ADDED)
                        .relationship(newRel)
                        .build());
            } else if (!isRelationshipEqual(oldRel, newRel)) {
                modified.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.MODIFIED)
                        .relationship(newRel)
                        .changeDetail(getRelationshipChangeDetail(oldRel, newRel))
                        .build());
                analyzeRelationshipChanges(oldRel, newRel, modified);
            }
        });
        oldEntity.getRelationships().forEach(oldRel -> {
            if (newEntity.getRelationships().stream().noneMatch(r -> r.getType().equals(oldRel.getType()) && r.getColumn().equals(oldRel.getColumn()))) {
                modified.getRelationshipDiffs().add(DiffResult.RelationshipDiff.builder()
                        .type(DiffResult.RelationshipDiff.Type.DROPPED)
                        .relationship(oldRel)
                        .build());
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
        return Objects.equals(oldCol.getTableName(), newCol.getTableName()) &&
                Objects.equals(oldCol.getColumnName(), newCol.getColumnName()) &&
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
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues()) &&
                oldCol.isLob() == newCol.isLob() &&
                oldCol.getJdbcType() == newCol.getJdbcType() &&
                oldCol.getFetchType() == newCol.getFetchType() &&
                oldCol.isOptional() == newCol.isOptional() &&
                oldCol.isVersion() == newCol.isVersion() &&
                Objects.equals(oldCol.getConversionClass(), newCol.getConversionClass()) &&
                oldCol.getTemporalType() == newCol.getTemporalType();
    }

    private boolean isColumnEqualExceptName(ColumnModel oldCol, ColumnModel newCol) {
        return Objects.equals(oldCol.getTableName(), newCol.getTableName()) &&
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
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues()) &&
                oldCol.isLob() == newCol.isLob() &&
                oldCol.getJdbcType() == newCol.getJdbcType() &&
                oldCol.getFetchType() == newCol.getFetchType() &&
                oldCol.isOptional() == newCol.isOptional() &&
                oldCol.isVersion() == newCol.isVersion() &&
                Objects.equals(oldCol.getConversionClass(), newCol.getConversionClass()) &&
                oldCol.getTemporalType() == newCol.getTemporalType();
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
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues()) &&
                oldCol.isLob() == newCol.isLob() &&
                oldCol.getJdbcType() == newCol.getJdbcType() &&
                Objects.equals(oldCol.getConversionClass(), newCol.getConversionClass()) &&
                oldCol.getTemporalType() == newCol.getTemporalType();
    }

    private String getColumnChangeDetail(ColumnModel oldCol, ColumnModel newCol) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldCol.getTableName(), newCol.getTableName())) {
            detail.append("tableName changed from ").append(oldCol.getTableName()).append(" to ").append(newCol.getTableName()).append("; ");
        }
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
        if (oldCol.isLob() != newCol.isLob()) {
            detail.append("isLob changed from ").append(oldCol.isLob()).append(" to ").append(newCol.isLob()).append("; ");
        }
        if (oldCol.getJdbcType() != newCol.getJdbcType()) {
            detail.append("jdbcType changed from ").append(oldCol.getJdbcType()).append(" to ").append(newCol.getJdbcType()).append("; ");
        }
        if (oldCol.getFetchType() != newCol.getFetchType()) {
            detail.append("fetchType changed from ").append(oldCol.getFetchType()).append(" to ").append(newCol.getFetchType()).append("; ");
        }
        if (oldCol.isOptional() != newCol.isOptional()) {
            detail.append("isOptional changed from ").append(oldCol.isOptional()).append(" to ").append(newCol.isOptional()).append("; ");
        }
        if (oldCol.isVersion() != newCol.isVersion()) {
            detail.append("isVersion changed from ").append(oldCol.isVersion()).append(" to ").append(newCol.isVersion()).append("; ");
        }
        if (!Objects.equals(oldCol.getConversionClass(), newCol.getConversionClass())) {
            detail.append("conversionClass changed from ").append(oldCol.getConversionClass()).append(" to ").append(newCol.getConversionClass()).append("; ");
        }
        if (oldCol.getTemporalType() != newCol.getTemporalType()) {
            detail.append("temporalType changed from ").append(oldCol.getTemporalType()).append(" to ").append(newCol.getTemporalType()).append("; ");
        }
        if (!Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues())) {
            detail.append("enumValues changed; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }

    private boolean isSequenceEqual(SequenceModel oldSeq, SequenceModel newSeq) {
        return oldSeq.getInitialValue() == newSeq.getInitialValue() &&
                oldSeq.getAllocationSize() == newSeq.getAllocationSize() &&
                oldSeq.getCache() == newSeq.getCache() &&
                oldSeq.getMinValue() == newSeq.getMinValue() &&
                oldSeq.getMaxValue() == newSeq.getMaxValue() &&
                Objects.equals(oldSeq.getSchema(), newSeq.getSchema()) &&
                Objects.equals(oldSeq.getCatalog(), newSeq.getCatalog());
    }

    private String getSequenceChangeDetail(SequenceModel oldSeq, SequenceModel newSeq) {
        StringBuilder detail = new StringBuilder();
        if (oldSeq.getInitialValue() != newSeq.getInitialValue()) {
            detail.append("initialValue changed from ").append(oldSeq.getInitialValue()).append(" to ").append(newSeq.getInitialValue()).append("; ");
        }
        if (oldSeq.getAllocationSize() != newSeq.getAllocationSize()) {
            detail.append("allocationSize changed from ").append(oldSeq.getAllocationSize()).append(" to ").append(newSeq.getAllocationSize()).append("; ");
        }
        if (oldSeq.getCache() != newSeq.getCache()) {
            detail.append("cache changed from ").append(oldSeq.getCache()).append(" to ").append(newSeq.getCache()).append("; ");
        }
        if (oldSeq.getMinValue() != newSeq.getMinValue()) {
            detail.append("minValue changed from ").append(oldSeq.getMinValue()).append(" to ").append(newSeq.getMinValue()).append("; ");
        }
        if (oldSeq.getMaxValue() != newSeq.getMaxValue()) {
            detail.append("maxValue changed from ").append(oldSeq.getMaxValue()).append(" to ").append(newSeq.getMaxValue()).append("; ");
        }
        if (!Objects.equals(oldSeq.getSchema(), newSeq.getSchema())) {
            detail.append("schema changed from ").append(oldSeq.getSchema()).append(" to ").append(newSeq.getSchema()).append("; ");
        }
        if (!Objects.equals(oldSeq.getCatalog(), newSeq.getCatalog())) {
            detail.append("catalog changed from ").append(oldSeq.getCatalog()).append(" to ").append(newSeq.getCatalog()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
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
                oldCons.getColumns().equals(newCons.getColumns()) &&
                Objects.equals(oldCons.getReferencedTable(), newCons.getReferencedTable()) &&
                oldCons.getReferencedColumns().equals(newCons.getReferencedColumns()) &&
                oldCons.getOnDelete() == newCons.getOnDelete() &&
                oldCons.getOnUpdate() == newCons.getOnUpdate();
    }

    private String getConstraintChangeDetail(ConstraintModel oldCons, ConstraintModel newCons) {
        StringBuilder detail = new StringBuilder();
        if (!Objects.equals(oldCons.getType(), newCons.getType())) {
            detail.append("type changed from ").append(oldCons.getType()).append(" to ").append(newCons.getType()).append("; ");
        }
        if (!oldCons.getColumns().equals(newCons.getColumns())) {
            detail.append("columns changed from ").append(oldCons.getColumns()).append(" to ").append(newCons.getColumns()).append("; ");
        }
        if (!Objects.equals(oldCons.getReferencedTable(), newCons.getReferencedTable())) {
            detail.append("referencedTable changed from ").append(oldCons.getReferencedTable()).append(" to ").append(newCons.getReferencedTable()).append("; ");
        }
        if (!oldCons.getReferencedColumns().equals(newCons.getReferencedColumns())) {
            detail.append("referencedColumns changed from ").append(oldCons.getReferencedColumns()).append(" to ").append(newCons.getReferencedColumns()).append("; ");
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
                oldRel.isMapsId() == newRel.isMapsId() &&
                oldRel.getCascadeTypes().equals(newRel.getCascadeTypes()) &&
                oldRel.isOrphanRemoval() == newRel.isOrphanRemoval() &&
                oldRel.getFetchType() == newRel.getFetchType();
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
        if (!oldRel.getCascadeTypes().equals(newRel.getCascadeTypes())) {
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

    private void analyzeColumnChanges(ColumnModel oldCol, ColumnModel newCol, DiffResult.ModifiedEntity modified) {
        // Narrowing Conversion Check
        if (!Objects.equals(oldCol.getJavaType(), newCol.getJavaType())) {
            String change = analyzeTypeConversion(oldCol.getJavaType(), newCol.getJavaType());
            if (change.startsWith("Narrowing")) {
                modified.getWarnings().add("Dangerous type conversion in column " + newCol.getColumnName() + ": " + change);
            } else if (change.startsWith("Widening")) {
                modified.getWarnings().add("Safe type conversion in column " + newCol.getColumnName() + ": " + change);
            }
        }
        if (oldCol.getLength() > newCol.getLength() && newCol.getLength() > 0) {
            modified.getWarnings().add("Dangerous length reduction in column " + newCol.getColumnName() +
                    " from " + oldCol.getLength() + " to " + newCol.getLength() + "; may cause data truncation.");
        }
        if (oldCol.getFetchType() != newCol.getFetchType()) {
            modified.getWarnings().add("Fetch strategy changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getFetchType() + " to " + newCol.getFetchType() +
                    "; may impact data retrieval performance.");
        }
        if (!Objects.equals(oldCol.getConversionClass(), newCol.getConversionClass())) {
            modified.getWarnings().add("Converter changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getConversionClass() + " to " + newCol.getConversionClass() +
                    "; verify data compatibility.");
        }
    }

    private void analyzeEnumChanges(ColumnModel oldCol, ColumnModel newCol, DiffResult.ModifiedEntity modified) {
        if (!Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues())) {
            List<String> oldEnums = Arrays.asList(oldCol.getEnumValues());
            List<String> newEnums = Arrays.asList(newCol.getEnumValues());
            List<String> added = new ArrayList<>(newEnums);
            added.removeAll(oldEnums);
            List<String> removed = new ArrayList<>(oldEnums);
            removed.removeAll(newEnums);

            // Check order change for ORDINAL mapping
            if (!oldCol.isEnumStringMapping() && !newCol.isEnumStringMapping()) {
                boolean orderChanged = false;
                for (int i = 0; i < Math.min(oldEnums.size(), newEnums.size()); i++) {
                    if (!oldEnums.get(i).equals(newEnums.get(i))) {
                        orderChanged = true;
                        break;
                    }
                }
                if (orderChanged) {
                    modified.getWarnings().add("Dangerous enum order change in column " + newCol.getColumnName() +
                            "; ORDINAL mapping may cause incorrect data mapping!");
                }
            }

            // Check removed constants
            if (!removed.isEmpty()) {
                modified.getWarnings().add("Enum constants removed in column " + newCol.getColumnName() + ": " + removed +
                        "; existing data may become invalid.");
            }

            // Check added constants
            if (!added.isEmpty()) {
                modified.getWarnings().add("Enum constants added in column " + newCol.getColumnName() + ": " + added +
                        "; generally safe but verify application logic.");
            }
        }
    }

    private void analyzeRelationshipChanges(RelationshipModel oldRel, RelationshipModel newRel, DiffResult.ModifiedEntity modified) {
        if (!oldRel.getCascadeTypes().equals(newRel.getCascadeTypes())) {
            List<CascadeType> added = new ArrayList<>(newRel.getCascadeTypes());
            added.removeAll(oldRel.getCascadeTypes());
            List<CascadeType> removed = new ArrayList<>(oldRel.getCascadeTypes());
            removed.removeAll(newRel.getCascadeTypes());
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

    private String analyzeTypeConversion(String oldType, String newType) {
        // Simple type conversion analysis
        Map<String, Integer> typeSizes = new HashMap<>();
        typeSizes.put("byte", 1);
        typeSizes.put("short", 2);
        typeSizes.put("int", 4);
        typeSizes.put("long", 8);
        typeSizes.put("float", 4);
        typeSizes.put("double", 8);
        typeSizes.put("java.lang.Byte", 1);
        typeSizes.put("java.lang.Short", 2);
        typeSizes.put("java.lang.Integer", 4);
        typeSizes.put("java.lang.Long", 8);
        typeSizes.put("java.lang.Float", 4);
        typeSizes.put("java.lang.Double", 8);

        Integer oldSize = typeSizes.get(oldType);
        Integer newSize = typeSizes.get(newType);

        if (oldSize != null && newSize != null) {
            if (oldSize > newSize) {
                return "Narrowing conversion from " + oldType + " to " + newType + "; may cause data loss.";
            } else if (oldSize < newSize) {
                return "Widening conversion from " + oldType + " to " + newType + "; generally safe.";
            }
        }
        return "Type changed from " + oldType + " to " + newType + "; verify compatibility.";
    }
}