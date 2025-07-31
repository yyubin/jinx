package org.jinx.migration.differs;

import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

import java.util.*;

public class ColumnDiffer implements EntityComponentDiffer {
    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        Map<String, ColumnModel> oldColumns = new HashMap<>(oldEntity.getColumns());
        Map<String, ColumnModel> newColumns = new HashMap<>(newEntity.getColumns());
        Set<String> processedNewColumns = new HashSet<>();

        for (Map.Entry<String, ColumnModel> newEntry : newColumns.entrySet()) {
            Optional<Map.Entry<String, ColumnModel>> renamedMatch = oldColumns.entrySet().stream()
                    .filter(oldEntry -> !newColumns.containsKey(oldEntry.getKey()) &&
                            isColumnAttributesEqual(oldEntry.getValue(), newEntry.getValue()))
                    .findFirst();

            if (renamedMatch.isPresent()) {
                String oldName = renamedMatch.get().getKey();
                ColumnModel oldColumn = renamedMatch.get().getValue();
                ColumnModel newColumn = newEntry.getValue();
                result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.RENAMED)
                        .column(newColumn)
                        .oldColumn(oldColumn)
                        .changeDetail("Column renamed from " + oldName + " to " + newEntry.getKey())
                        .build());
                if (!isColumnEqualExceptName(oldColumn, newColumn)) {
                    result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
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

        // Detect added/modified columns
        for (Map.Entry<String, ColumnModel> newEntry : newColumns.entrySet()) {
            if (processedNewColumns.contains(newEntry.getKey())) continue;

            String newName = newEntry.getKey();
            ColumnModel newColumn = newEntry.getValue();

            if (!oldColumns.containsKey(newName)) {
                result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.ADDED)
                        .column(newColumn)
                        .build());
            } else {
                ColumnModel oldColumn = oldColumns.get(newName);
                if (isEnumMappingChanged(oldColumn, newColumn)) {
                    result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn)
                            .oldColumn(oldColumn)
                            .changeDetail("Enum mapping changed " + (oldColumn.isEnumStringMapping() ? "STRING -> ORDINAL" : "ORDINAL -> STRING"))
                            .build());
                    result.getWarnings().add("Enum mapping changed on column " + newColumn.getColumnName() +
                            " from " + (oldColumn.isEnumStringMapping() ? "STRING" : "ORDINAL") + " to " +
                            (newColumn.isEnumStringMapping() ? "STRING" : "ORDINAL") + "; verify data compatibility.");
                    analyzeEnumChanges(oldColumn, newColumn, result);
                } else if (!isColumnEqual(oldColumn, newColumn)) {
                    result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                            .type(DiffResult.ColumnDiff.Type.MODIFIED)
                            .column(newColumn)
                            .oldColumn(oldColumn)
                            .changeDetail(getColumnChangeDetail(oldColumn, newColumn))
                            .build());
                    analyzeColumnChanges(oldColumn, newColumn, result);
                    if (isEnum(oldColumn) && isEnum(newColumn)) {
                        analyzeEnumChanges(oldColumn, newColumn, result);
                    }
                }
                oldColumns.remove(newName);
            }
        }

        // Detect dropped columns
        oldColumns.forEach((name, oldColumn) -> result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                .type(DiffResult.ColumnDiff.Type.DROPPED)
                .column(oldColumn)
                .build()));
    }

    private boolean isColumnEqual(ColumnModel oldCol, ColumnModel newCol) {
        return Optional.ofNullable(oldCol.getColumnName()).equals(Optional.ofNullable(newCol.getColumnName())) &&
                Optional.ofNullable(oldCol.getTableName()).equals(Optional.ofNullable(newCol.getTableName())) &&
                Optional.ofNullable(oldCol.getJavaType()).equals(Optional.ofNullable(newCol.getJavaType())) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                Optional.ofNullable(oldCol.getDefaultValue()).equals(Optional.ofNullable(newCol.getDefaultValue())) &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                Optional.ofNullable(oldCol.getSequenceName()).equals(Optional.ofNullable(newCol.getSequenceName())) &&
                Optional.ofNullable(oldCol.getTableGeneratorName()).equals(Optional.ofNullable(newCol.getTableGeneratorName())) &&
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
                Optional.ofNullable(oldCol.getConversionClass()).equals(Optional.ofNullable(newCol.getConversionClass())) &&
                oldCol.getTemporalType() == newCol.getTemporalType();
    }

    private boolean isColumnEqualExceptName(ColumnModel oldCol, ColumnModel newCol) {
        return Optional.ofNullable(oldCol.getTableName()).equals(Optional.ofNullable(newCol.getTableName())) &&
                Optional.ofNullable(oldCol.getJavaType()).equals(Optional.ofNullable(newCol.getJavaType())) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                Optional.ofNullable(oldCol.getDefaultValue()).equals(Optional.ofNullable(newCol.getDefaultValue())) &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                Optional.ofNullable(oldCol.getSequenceName()).equals(Optional.ofNullable(newCol.getSequenceName())) &&
                Optional.ofNullable(oldCol.getTableGeneratorName()).equals(Optional.ofNullable(newCol.getTableGeneratorName())) &&
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
                Optional.ofNullable(oldCol.getConversionClass()).equals(Optional.ofNullable(newCol.getConversionClass())) &&
                oldCol.getTemporalType() == newCol.getTemporalType();
    }

    private boolean isColumnAttributesEqual(ColumnModel oldCol, ColumnModel newCol) {
        return Optional.ofNullable(oldCol.getJavaType()).equals(Optional.ofNullable(newCol.getJavaType())) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.isUnique() == newCol.isUnique() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                Optional.ofNullable(oldCol.getSequenceName()).equals(Optional.ofNullable(newCol.getSequenceName())) &&
                Optional.ofNullable(oldCol.getTableGeneratorName()).equals(Optional.ofNullable(newCol.getTableGeneratorName())) &&
                oldCol.isEnumStringMapping() == newCol.isEnumStringMapping() &&
                Arrays.equals(oldCol.getEnumValues(), newCol.getEnumValues()) &&
                oldCol.isLob() == newCol.isLob() &&
                oldCol.getJdbcType() == newCol.getJdbcType() &&
                Optional.ofNullable(oldCol.getConversionClass()).equals(Optional.ofNullable(newCol.getConversionClass())) &&
                oldCol.getTemporalType() == newCol.getTemporalType();
    }

    private String getColumnChangeDetail(ColumnModel oldCol, ColumnModel newCol) {
        StringBuilder detail = new StringBuilder();
        if (!Optional.ofNullable(oldCol.getTableName()).equals(Optional.ofNullable(newCol.getTableName()))) {
            detail.append("tableName changed from ").append(oldCol.getTableName()).append(" to ").append(newCol.getTableName()).append("; ");
        }
        if (!Optional.ofNullable(oldCol.getJavaType()).equals(Optional.ofNullable(newCol.getJavaType()))) {
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
        if (!Optional.ofNullable(oldCol.getDefaultValue()).equals(Optional.ofNullable(newCol.getDefaultValue()))) {
            detail.append("defaultValue changed from ").append(oldCol.getDefaultValue()).append(" to ").append(newCol.getDefaultValue()).append("; ");
        }
        if (oldCol.getGenerationStrategy() != newCol.getGenerationStrategy()) {
            detail.append("generationStrategy changed from ").append(oldCol.getGenerationStrategy()).append(" to ").append(newCol.getGenerationStrategy()).append("; ");
        }
        if (!Optional.ofNullable(oldCol.getSequenceName()).equals(Optional.ofNullable(newCol.getSequenceName()))) {
            detail.append("sequenceName changed from ").append(oldCol.getSequenceName()).append(" to ").append(newCol.getSequenceName()).append("; ");
        }
        if (!Optional.ofNullable(oldCol.getTableGeneratorName()).equals(Optional.ofNullable(newCol.getTableGeneratorName()))) {
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
        // ✨ 일관성 유지를 위해 추가된 코드
        if (oldCol.isManualPrimaryKey() != newCol.isManualPrimaryKey()) {
            detail.append("isManualPrimaryKey changed from ").append(oldCol.isManualPrimaryKey()).append(" to ").append(newCol.isManualPrimaryKey()).append("; ");
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
        if (!Optional.ofNullable(oldCol.getConversionClass()).equals(Optional.ofNullable(newCol.getConversionClass()))) {
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

    private void analyzeColumnChanges(ColumnModel oldCol, ColumnModel newCol, DiffResult.ModifiedEntity modified) {
        if (oldCol.isNullable() && !newCol.isNullable()) {
            modified.getWarnings().add("Nullable column " + newCol.getColumnName() +
                    " is now NOT NULL; existing null data will violate constraint.");
        }
        if (!Optional.ofNullable(oldCol.getJavaType()).equals(Optional.ofNullable(newCol.getJavaType()))) {
            String change = analyzeTypeConversion(oldCol.getJavaType(), newCol.getJavaType());
            if (change.startsWith("Narrowing")) {
                modified.getWarnings().add("Dangerous type conversion in column " + newCol.getColumnName() + ": " + change);
            } else if (change.startsWith("Widening")) {
                modified.getWarnings().add("Safe type conversion in column " + newCol.getColumnName() + ": " + change);
            } else {
                modified.getWarnings().add("Type conversion in column " + newCol.getColumnName() + ": " + change);
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
        if (!Optional.ofNullable(oldCol.getConversionClass()).equals(Optional.ofNullable(newCol.getConversionClass()))) {
            modified.getWarnings().add("Converter changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getConversionClass() + " to " + newCol.getConversionClass() +
                    "; verify data compatibility.");
        }
    }

    private void analyzeEnumChanges(ColumnModel oldCol, ColumnModel newCol, DiffResult.ModifiedEntity modified) {
        List<String> newEnums = Arrays.asList(newCol.getEnumValues());
        List<String> oldEnums = Arrays.asList(oldCol.getEnumValues());
        List<String> added = new ArrayList<>(newEnums);
        added.removeAll(oldEnums);
        List<String> removed = new ArrayList<>(oldEnums);
        removed.removeAll(newEnums);

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

        if (!removed.isEmpty()) {
            modified.getWarnings().add("Enum constants removed in column " + newCol.getColumnName() + ": " + removed +
                    "; existing data may become invalid.");
        }
        if (!added.isEmpty()) {
            modified.getWarnings().add("Enum constants added in column " + newCol.getColumnName() + ": " + added +
                    "; generally safe but verify application logic.");
        }
    }

    private boolean isEnum(ColumnModel col) {
        return col.getEnumValues() != null && col.getEnumValues().length > 0;
    }

    private boolean isEnumMappingChanged(ColumnModel oldCol, ColumnModel newCol) {
        return isEnum(oldCol) && isEnum(newCol) && oldCol.isEnumStringMapping() != newCol.isEnumStringMapping();
    }

    private String analyzeTypeConversion(String oldType, String newType) {
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