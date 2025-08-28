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

    /**
     * Creates a pre-populated ColumnModel.ColumnModelBuilder for a Java field.
     *
     * The builder is initialized using JPA {@link Column}, {@link Id} and {@link EmbeddedId}
     * annotations present on the field and by applying any provided type hint and overrides.
     *
     * @param field the source field element to read annotations and type from
     * @param typeHint an optional type override; when non-null its string form is used for javaType
     * @param columnName the fallback column name used when neither an override nor @Column.name is present
     * @param context processing context (not documented as a parameter service)
     * @param overrides map of per-field override values; if an entry exists for the field's simple name it
     *                  takes precedence for the resolved column name
     * @return a configured but unbuilt ColumnModel.ColumnModelBuilder
     */
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

    /**
     * Build a ColumnModel.ColumnModelBuilder from an AttributeDescriptor, applying annotations and overrides.
     *
     * The returned builder is pre-populated with columnName, javaType, primary-key flag, nullability,
     * uniqueness, length, precision, scale, default value (from @Column.columnDefinition), and
     * GenerationStrategy.NONE. It does not call build().
     *
     * Column name resolution priority:
     * 1) explicit `columnName` parameter (if non-blank)
     * 2) entry in `overrides` keyed by the attribute's name (if non-blank)
     * 3) `@Column.name()` on the attribute (if present and non-blank)
     * 4) the attribute's own name
     *
     * Table name resolution:
     * - If `overrides` contains a non-blank "tableName" value, it is applied.
     * - Otherwise, if `@Column.table()` is present and non-blank, that value is applied.
     *
     * @param attribute   the AttributeDescriptor representing the source attribute
     * @param type        the TypeMirror for the attribute's Java type (used to set javaType)
     * @param columnName  an initial column name candidate (may be overridden per resolution rules)
     * @param context     processing context (omitted from parameter documentation as a common utility)
     * @param overrides   runtime overrides; may contain an entry keyed by the attribute name to override the column name,
     *                    and may contain "tableName" to override the table name
     * @return a pre-populated ColumnModel.ColumnModelBuilder (not yet built)
     */
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
     * Resolve the final column name for an attribute using priority-based rules.
     *
     * Priority (highest → lowest):
     * 1) the explicit `columnName` parameter,
     * 2) an entry in `overrides` keyed by the attribute's name,
     * 3) the `name` value from the optional `@Column` annotation,
     * 4) the attribute's own name.
     *
     * @param attribute the attribute descriptor whose name may be used as a fallback
     * @param columnName an explicit column name override (may be null/blank)
     * @param column the JPA `@Column` annotation instance, if present
     * @param overrides map of attribute-name → column-name overrides
     * @return the resolved column name (never null; may be an empty string only if inputs are empty)
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
    
    /**
     * Returns true if the given string is non-null and not empty.
     *
     * @param s the string to check
     * @return true if {@code s} is non-null and has length > 0; false otherwise
     */
    private static boolean isNotBlank(String s) {
        return s != null && !s.isEmpty();
    }

}
