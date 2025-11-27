package org.jinx.migration.differs;

import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

import java.util.*;

public class SimpleColumnDiffer implements EntityComponentDiffer {

    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        // display 기준 맵 생성
        Map<String, ColumnModel> oldColumns = new HashMap<>();
        oldEntity.getColumns().forEach((key, value) -> oldColumns.put(key.display(), value));
        Map<String, ColumnModel> newColumns = new HashMap<>();
        newEntity.getColumns().forEach((key, value) -> newColumns.put(key.display(), value));

        // Detect added/modified columns
        for (Map.Entry<String, ColumnModel> newEntry : newColumns.entrySet()) {
            String newName = newEntry.getKey();
            ColumnModel newColumn = newEntry.getValue();

            if (!oldColumns.containsKey(newName)) {
                // Column does not exist in old schema -> ADDED
                result.getColumnDiffs().add(DiffResult.ColumnDiff.builder()
                        .type(DiffResult.ColumnDiff.Type.ADDED)
                        .column(newColumn)
                        .build());
            } else {
                // Column exists in both schemas -> check for modifications
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
        return eq(oldCol.getColumnName(), newCol.getColumnName()) &&
                eq(oldCol.getTableName(), newCol.getTableName()) &&
                eq(oldCol.getJavaType(), newCol.getJavaType()) &&
                eq(oldCol.getComment(), newCol.getComment()) &&
                oldCol.isPrimaryKey() == newCol.isPrimaryKey() &&
                oldCol.isNullable() == newCol.isNullable() &&
                oldCol.getLength() == newCol.getLength() &&
                oldCol.getPrecision() == newCol.getPrecision() &&
                oldCol.getScale() == newCol.getScale() &&
                eq(oldCol.getDefaultValue(), newCol.getDefaultValue()) &&
                oldCol.getGenerationStrategy() == newCol.getGenerationStrategy() &&
                eq(oldCol.getSequenceName(), newCol.getSequenceName()) &&
                eq(oldCol.getTableGeneratorName(), newCol.getTableGeneratorName()) &&
                oldCol.getIdentityStartValue() == newCol.getIdentityStartValue() &&
                oldCol.getIdentityIncrement() == newCol.getIdentityIncrement() &&
                oldCol.getIdentityCache() == newCol.getIdentityCache() &&
                oldCol.getIdentityMinValue() == newCol.getIdentityMinValue() &&
                oldCol.getIdentityMaxValue() == newCol.getIdentityMaxValue() &&
                eqArr(oldCol.getIdentityOptions(), newCol.getIdentityOptions()) &&
                oldCol.isManualPrimaryKey() == newCol.isManualPrimaryKey() &&
                oldCol.isEnumStringMapping() == newCol.isEnumStringMapping() &&
                eqArr(oldCol.getEnumValues(), newCol.getEnumValues()) &&
                oldCol.isLob() == newCol.isLob() &&
                oldCol.getJdbcType() == newCol.getJdbcType() &&
                oldCol.getFetchType() == newCol.getFetchType() &&
                oldCol.isOptional() == newCol.isOptional() &&
                oldCol.isVersion() == newCol.isVersion() &&
                eq(oldCol.getConversionClass(), newCol.getConversionClass()) &&
                oldCol.getTemporalType() == newCol.getTemporalType() &&
                eq(oldCol.getSqlTypeOverride(), newCol.getSqlTypeOverride()) &&
                oldCol.getColumnKind() == newCol.getColumnKind() &&
                oldCol.getDiscriminatorType() == newCol.getDiscriminatorType() &&
                eq(oldCol.getColumnDefinition(), newCol.getColumnDefinition()) &&
                eq(oldCol.getOptions(), newCol.getOptions()) &&
                oldCol.isMapKey() == newCol.isMapKey() &&
                eq(oldCol.getMapKeyType(), newCol.getMapKeyType()) &&
                eqArr(oldCol.getMapKeyEnumValues(), newCol.getMapKeyEnumValues()) &&
                oldCol.getMapKeyTemporalType() == newCol.getMapKeyTemporalType();
    }

    private String getColumnChangeDetail(ColumnModel oldCol, ColumnModel newCol) {
        List<String> changes = new ArrayList<>();

        if (!eq(oldCol.getTableName(), newCol.getTableName())) {
            changes.add("tableName changed from " + oldCol.getTableName() + " to " + newCol.getTableName());
        }
        if (!eq(oldCol.getJavaType(), newCol.getJavaType())) {
            changes.add("javaType changed from " + oldCol.getJavaType() + " to " + newCol.getJavaType());
        }
        if (oldCol.isPrimaryKey() != newCol.isPrimaryKey()) {
            changes.add("isPrimaryKey changed from " + oldCol.isPrimaryKey() + " to " + newCol.isPrimaryKey());
        }
        if (oldCol.isNullable() != newCol.isNullable()) {
            changes.add("isNullable changed from " + oldCol.isNullable() + " to " + newCol.isNullable());
        }
        if (oldCol.getLength() != newCol.getLength()) {
            changes.add("length changed from " + oldCol.getLength() + " to " + newCol.getLength());
        }
        if (oldCol.getPrecision() != newCol.getPrecision()) {
            changes.add("precision changed from " + oldCol.getPrecision() + " to " + newCol.getPrecision());
        }
        if (oldCol.getScale() != newCol.getScale()) {
            changes.add("scale changed from " + oldCol.getScale() + " to " + newCol.getScale());
        }
        if (!eq(oldCol.getDefaultValue(), newCol.getDefaultValue())) {
            changes.add("defaultValue changed from " + oldCol.getDefaultValue() + " to " + newCol.getDefaultValue());
        }
        if (oldCol.getGenerationStrategy() != newCol.getGenerationStrategy()) {
            changes.add("generationStrategy changed from " + oldCol.getGenerationStrategy() + " to " + newCol.getGenerationStrategy());
        }
        if (!eq(oldCol.getSequenceName(), newCol.getSequenceName())) {
            changes.add("sequenceName changed from " + oldCol.getSequenceName() + " to " + newCol.getSequenceName());
        }
        if (!eq(oldCol.getTableGeneratorName(), newCol.getTableGeneratorName())) {
            changes.add("tableGeneratorName changed from " + oldCol.getTableGeneratorName() + " to " + newCol.getTableGeneratorName());
        }
        if (oldCol.getIdentityStartValue() != newCol.getIdentityStartValue()) {
            changes.add("identityStartValue changed from " + oldCol.getIdentityStartValue() + " to " + newCol.getIdentityStartValue());
        }
        if (oldCol.getIdentityIncrement() != newCol.getIdentityIncrement()) {
            changes.add("identityIncrement changed from " + oldCol.getIdentityIncrement() + " to " + newCol.getIdentityIncrement());
        }
        if (oldCol.getIdentityCache() != newCol.getIdentityCache()) {
            changes.add("identityCache changed from " + oldCol.getIdentityCache() + " to " + newCol.getIdentityCache());
        }
        if (oldCol.getIdentityMinValue() != newCol.getIdentityMinValue()) {
            changes.add("identityMinValue changed from " + oldCol.getIdentityMinValue() + " to " + newCol.getIdentityMinValue());
        }
        if (oldCol.getIdentityMaxValue() != newCol.getIdentityMaxValue()) {
            changes.add("identityMaxValue changed from " + oldCol.getIdentityMaxValue() + " to " + newCol.getIdentityMaxValue());
        }
        if (!eqArr(oldCol.getIdentityOptions(), newCol.getIdentityOptions())) {
            changes.add("identityOptions changed from " + arrToString(oldCol.getIdentityOptions()) + " to " + arrToString(newCol.getIdentityOptions()));
        }
        if (oldCol.isManualPrimaryKey() != newCol.isManualPrimaryKey()) {
            changes.add("isManualPrimaryKey changed from " + oldCol.isManualPrimaryKey() + " to " + newCol.isManualPrimaryKey());
        }
        if (oldCol.isLob() != newCol.isLob()) {
            changes.add("isLob changed from " + oldCol.isLob() + " to " + newCol.isLob());
        }
        if (oldCol.getJdbcType() != newCol.getJdbcType()) {
            changes.add("jdbcType changed from " + oldCol.getJdbcType() + " to " + newCol.getJdbcType());
        }
        if (oldCol.getFetchType() != newCol.getFetchType()) {
            changes.add("fetchType changed from " + oldCol.getFetchType() + " to " + newCol.getFetchType());
        }
        if (oldCol.isOptional() != newCol.isOptional()) {
            changes.add("isOptional changed from " + oldCol.isOptional() + " to " + newCol.isOptional());
        }
        if (oldCol.isVersion() != newCol.isVersion()) {
            changes.add("isVersion changed from " + oldCol.isVersion() + " to " + newCol.isVersion());
        }
        if (!eq(oldCol.getConversionClass(), newCol.getConversionClass())) {
            changes.add("conversionClass changed from " + oldCol.getConversionClass() + " to " + newCol.getConversionClass());
        }
        if (oldCol.getTemporalType() != newCol.getTemporalType()) {
            changes.add("temporalType changed from " + oldCol.getTemporalType() + " to " + newCol.getTemporalType());
        }
        if (!eqArr(oldCol.getEnumValues(), newCol.getEnumValues())) {
            changes.add("enumValues changed");
        }
        if (!eq(oldCol.getComment(), newCol.getComment())) {
            changes.add("comment changed from " + oldCol.getComment() + " to " + newCol.getComment());
        }
        if (!eq(oldCol.getSqlTypeOverride(), newCol.getSqlTypeOverride())) {
            changes.add("sqlTypeOverride changed from " + oldCol.getSqlTypeOverride() + " to " + newCol.getSqlTypeOverride());
        }
        if (oldCol.getColumnKind() != newCol.getColumnKind()) {
            changes.add("columnKind changed from " + oldCol.getColumnKind() + " to " + newCol.getColumnKind());
        }
        if (oldCol.getDiscriminatorType() != newCol.getDiscriminatorType()) {
            changes.add("discriminatorType changed from " + oldCol.getDiscriminatorType() + " to " + newCol.getDiscriminatorType());
        }
        if (!eq(oldCol.getColumnDefinition(), newCol.getColumnDefinition())) {
            changes.add("columnDefinition changed from " + oldCol.getColumnDefinition() + " to " + newCol.getColumnDefinition());
        }
        if (!eq(oldCol.getOptions(), newCol.getOptions())) {
            changes.add("options changed from " + oldCol.getOptions() + " to " + newCol.getOptions());
        }
        if (oldCol.isMapKey() != newCol.isMapKey()) {
            changes.add("isMapKey changed from " + oldCol.isMapKey() + " to " + newCol.isMapKey());
        }
        if (!eq(oldCol.getMapKeyType(), newCol.getMapKeyType())) {
            changes.add("mapKeyType changed from " + oldCol.getMapKeyType() + " to " + newCol.getMapKeyType());
        }
        if (!eqArr(oldCol.getMapKeyEnumValues(), newCol.getMapKeyEnumValues())) {
            changes.add("mapKeyEnumValues changed from " + arrToString(oldCol.getMapKeyEnumValues()) + " to " + arrToString(newCol.getMapKeyEnumValues()));
        }
        if (oldCol.getMapKeyTemporalType() != newCol.getMapKeyTemporalType()) {
            changes.add("mapKeyTemporalType changed from " + oldCol.getMapKeyTemporalType() + " to " + newCol.getMapKeyTemporalType());
        }

        return String.join("; ", changes);
    }

    private void analyzeColumnChanges(ColumnModel oldCol, ColumnModel newCol, DiffResult.ModifiedEntity modified) {
        if (oldCol.isNullable() && !newCol.isNullable()) {
            modified.getWarnings().add("Nullable column " + newCol.getColumnName() +
                    " is now NOT NULL; existing null data will violate constraint.");
        }
        if (!eq(oldCol.getJavaType(), newCol.getJavaType())) {
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
        if (!eq(oldCol.getConversionClass(), newCol.getConversionClass())) {
            modified.getWarnings().add("Converter changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getConversionClass() + " to " + newCol.getConversionClass() +
                    "; verify data compatibility.");
        }
        if (oldCol.isPrimaryKey() != newCol.isPrimaryKey()) {
            modified.getWarnings().add("Primary key flag changed in column " + newCol.getColumnName() +
                    " from " + oldCol.isPrimaryKey() + " to " + newCol.isPrimaryKey() +
                    "; significant schema structure change.");
        }
        if (oldCol.getGenerationStrategy() != newCol.getGenerationStrategy()) {
            modified.getWarnings().add("Generation strategy changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getGenerationStrategy() + " to " + newCol.getGenerationStrategy() +
                    "; may require data migration.");
        }
        if (!eq(oldCol.getSqlTypeOverride(), newCol.getSqlTypeOverride())) {
            modified.getWarnings().add("SQL type override changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getSqlTypeOverride() + " to " + newCol.getSqlTypeOverride() +
                    "; actual column type may change.");
        }
        if (!eq(oldCol.getDefaultValue(), newCol.getDefaultValue())) {
            modified.getWarnings().add("Default value changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getDefaultValue() + " to " + newCol.getDefaultValue() +
                    "; may affect existing data.");
        }
        if (oldCol.getPrecision() > newCol.getPrecision() && newCol.getPrecision() > 0) {
            modified.getWarnings().add("Dangerous precision reduction in column " + newCol.getColumnName() +
                    " from " + oldCol.getPrecision() + " to " + newCol.getPrecision() + "; may cause data truncation.");
        }
        if (oldCol.getScale() > newCol.getScale() && newCol.getScale() >= 0) {
            modified.getWarnings().add("Dangerous scale reduction in column " + newCol.getColumnName() +
                    " from " + oldCol.getScale() + " to " + newCol.getScale() + "; may cause data truncation.");
        }
        if (oldCol.isLob() != newCol.isLob()) {
            modified.getWarnings().add("LOB flag changed in column " + newCol.getColumnName() +
                    " from " + oldCol.isLob() + " to " + newCol.isLob() +
                    "; significant storage and performance impact.");
        }
        if (oldCol.getJdbcType() != newCol.getJdbcType()) {
            modified.getWarnings().add("JDBC type changed in column " + newCol.getColumnName() +
                    " from " + oldCol.getJdbcType() + " to " + newCol.getJdbcType() +
                    "; verify type compatibility.");
        }
        if (oldCol.isMapKey() != newCol.isMapKey() || !eq(oldCol.getMapKeyType(), newCol.getMapKeyType()) ||
                !eqArr(oldCol.getMapKeyEnumValues(), newCol.getMapKeyEnumValues()) ||
                oldCol.getMapKeyTemporalType() != newCol.getMapKeyTemporalType()) {
            modified.getWarnings().add("MapKey metadata changed in column " + newCol.getColumnName() +
                    "; collection mapping semantics may change.");
        }
        if (oldCol.isVersion() != newCol.isVersion()) {
            modified.getWarnings().add("Version flag changed in column " + newCol.getColumnName() +
                    " from " + oldCol.isVersion() + " to " + newCol.isVersion() +
                    "; optimistic locking strategy may change.");
        }
        if (oldCol.getDiscriminatorType() != newCol.getDiscriminatorType() ||
                !eq(oldCol.getColumnDefinition(), newCol.getColumnDefinition()) ||
                !eq(oldCol.getOptions(), newCol.getOptions())) {
            modified.getWarnings().add("Discriminator metadata changed in column " + newCol.getColumnName() +
                    "; inheritance mapping may be affected.");
        }
    }

    private void analyzeEnumChanges(ColumnModel oldCol, ColumnModel newCol, DiffResult.ModifiedEntity modified) {
        List<String> newEnums = toListSafe(newCol.getEnumValues());
        List<String> oldEnums = toListSafe(oldCol.getEnumValues());
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

    private static boolean eq(Object a, Object b) {
        return Objects.equals(a, b);
    }

    private static boolean eqArr(Object[] a, Object[] b) {
        return Arrays.equals(a, b);
    }

    private static String arrToString(String[] a) {
        return Arrays.toString(a != null ? a : new String[0]);
    }

    private static List<String> toListSafe(String[] array) {
        return Arrays.asList(Optional.ofNullable(array).orElse(new String[0]));
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
