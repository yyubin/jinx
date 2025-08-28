package org.jinx.util;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public class ColumnBuilderFactory {

    public static ColumnModel.ColumnModelBuilder from(VariableElement field, TypeMirror typeHint, String columnName,
                                                      ProcessingContext context, Map<String, String> overrides) {
        Column column = field.getAnnotation(Column.class);
        String finalColumnName = overrides.getOrDefault(
                field.getSimpleName().toString(),
                column != null && !column.name().isEmpty() ? column.name() : columnName
        );

        return ColumnModel.builder()
                .columnName(finalColumnName)
                .javaType(typeHint != null ? typeHint.toString() : field.asType().toString())
                .isPrimaryKey(field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null)
                .isNullable(column == null || column.nullable())
                .isUnique(column != null && column.unique())
                .length(column != null ? column.length() : 255)
                .precision(column != null ? column.precision() : 0)
                .scale(column != null ? column.scale() : 0)
                .defaultValue(column != null && !column.columnDefinition().isEmpty() ? column.columnDefinition() : null)
                .generationStrategy(GenerationStrategy.NONE);
    }

    public static ColumnModel.ColumnModelBuilder fromAttributeDescriptor(AttributeDescriptor attribute, TypeMirror type, String columnName,
                                                                          ProcessingContext context, Map<String, String> overrides) {
        Column column = attribute.getAnnotation(Column.class);
        
        // Use same priority-based column name resolution as AttributeBasedEntityResolver
        String finalColumnName = determineColumnName(attribute, columnName, column, overrides);

        ColumnModel.ColumnModelBuilder builder = ColumnModel.builder()
                .columnName(finalColumnName)
                .javaType(type.toString())
                .isPrimaryKey(attribute.hasAnnotation(Id.class) || attribute.hasAnnotation(EmbeddedId.class))
                .isNullable(column == null || column.nullable())
                .isUnique(column != null && column.unique())
                .length(column != null ? column.length() : 255)
                .precision(column != null ? column.precision() : 0)
                .scale(column != null ? column.scale() : 0)
                .defaultValue(column != null && !column.columnDefinition().isEmpty() ? column.columnDefinition() : null)
                .generationStrategy(GenerationStrategy.NONE);
        
        // Apply table name override if provided
        String tableNameOverride = overrides.get("tableName");
        if (isNotBlank(tableNameOverride)) {
            builder.tableName(tableNameOverride);
        } else if (column != null && isNotBlank(column.table())) {
            builder.tableName(column.table());
        }
        
        return builder;
    }

    /**
     * Determine column name using priority-based resolution
     * Priority: explicit columnName > overrides > @Column.name() > attribute.name()
     */
    private static String determineColumnName(AttributeDescriptor attribute, String columnName, Column column, Map<String, String> overrides) {
        // Priority 1: Explicit parameter columnName (highest priority)
        if (isNotBlank(columnName)) {
            return columnName;
        }
        
        // Priority 2: Overrides map for this attribute  
        String attributeName = attribute.name();
        String overrideName = overrides.get(attributeName);
        if (isNotBlank(overrideName)) {
            return overrideName;
        }
        
        // Priority 3: @Column.name() annotation
        if (column != null && isNotBlank(column.name())) {
            return column.name();
        }
        
        // Priority 4: Attribute name (fallback)
        return attributeName;
    }
    
    private static boolean isNotBlank(String s) {
        return s != null && !s.isEmpty();
    }

}
