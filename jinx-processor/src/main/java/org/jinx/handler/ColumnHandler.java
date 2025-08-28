package org.jinx.handler;

import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.handler.builtins.AttributeBasedEntityResolver;
import org.jinx.handler.builtins.CollectionElementResolver;
import org.jinx.handler.builtins.EntityFieldResolver;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Map;

public class ColumnHandler {
    private final ProcessingContext context;
    private final SequenceHandler sequenceHandler;
    private final ColumnResolver fieldBasedResolver;
    private final ColumnResolver typeBasedResolver;
    private final AttributeColumnResolver attributeBasedResolver;

    /**
     * Create a ColumnHandler bound to the given processing context and sequence handler.
     *
     * Initializes the three column resolvers used by this handler:
     * - field-based resolver for collection element types,
     * - type-based resolver for entity fields,
     * - attribute-based resolver for attribute-driven resolution.
     */
    public ColumnHandler(ProcessingContext context, SequenceHandler sequenceHandler) {
        this.context = context;
        this.sequenceHandler = sequenceHandler;
        this.fieldBasedResolver = new CollectionElementResolver(context);
        this.typeBasedResolver = new EntityFieldResolver(context, sequenceHandler);
        this.attributeBasedResolver = new AttributeBasedEntityResolver(context, sequenceHandler);
    }

    /**
     * Create a ColumnModel for the given field using type-based resolution.
     *
     * Resolves column metadata from the provided field element. The
     * `overrides` map may supply resolver-specific override values (for example, a
     * custom table name or column attributes) that will be applied during
     * resolution.
     *
     * @param field the field element to resolve a column from
     * @param overrides resolver-specific override values; may be empty
     * @return the resolved ColumnModel
     */
    public ColumnModel createFrom(VariableElement field, Map<String, String> overrides) {
        return typeBasedResolver.resolve(field, null, null, overrides);
    }

    /**
     * Creates a ColumnModel for a field by resolving its (collection) element type.
     *
     * Resolves a column using the field-based resolver with the provided explicit element
     * type and column name. No overrides are applied.
     *
     * @param field the variable element representing the source field (used for resolution/diagnostics)
     * @param type  the element type to resolve into a column
     * @param columnName the desired column name (may be null or empty)
     * @return the resolved ColumnModel
     */
    public ColumnModel createFromFieldType(VariableElement field, TypeMirror type, String columnName) {
        return fieldBasedResolver.resolve(field, type, columnName, Map.of());
    }

    /**
     * Creates a ColumnModel for the given attribute, applying any overrides and ensuring the column's
     * table name is valid for the parent entity.
     *
     * Resolves the column using the attribute-based resolver, applies an optional "tableName"
     * entry from the overrides map (if present and non-empty), then validates the resulting table
     * name against the provided entity and falls back to the entity's primary table when invalid.
     *
     * @param attribute descriptor of the attribute to create a column for
     * @param entity parent entity used to validate/correct the column's table name
     * @param overrides optional overrides; recognizes the "tableName" key to force the column's table
     * @return the resolved and validated ColumnModel
     */
    public ColumnModel createFromAttribute(AttributeDescriptor attribute, EntityModel entity, Map<String, String> overrides) {
        ColumnModel column = attributeBasedResolver.resolve(attribute, null, null, overrides);
        String tableOverride = overrides.get("tableName");
        if (tableOverride != null && !tableOverride.isEmpty()) {
            column.setTableName(tableOverride);
        }
        validateAndCorrectTableName(column, attribute, entity);
        return column;
    }

    /**
     * Resolve a ColumnModel for an attribute using an explicit column type and name, and ensure its table name is valid for the parent entity.
     *
     * Resolves the column from the given AttributeDescriptor using the attribute-based resolver with the provided TypeMirror and columnName. After resolution, validates the column's table name against the provided EntityModel; if the column specifies an invalid table for that entity, the table name is replaced with the entity's primary table (a warning is emitted via the processing context).
     *
     * @param attribute  descriptor of the attribute the column is derived from
     * @param entity     parent entity model used to validate and possibly correct the column's table name
     * @param type       explicit column type to use for resolution
     * @param columnName explicit column name to use for resolution
     * @return the resolved ColumnModel (may be modified to use the entity's primary table if the original table name was invalid)
     */
    public ColumnModel createFromAttributeType(AttributeDescriptor attribute, EntityModel entity, TypeMirror type, String columnName) {
        ColumnModel column = attributeBasedResolver.resolve(attribute, type, columnName, Map.of());
        validateAndCorrectTableName(column, attribute, entity);
        return column;
    }

    /**
     * Ensure a ColumnModel's tableName is valid for its parent EntityModel, correcting it if necessary.
     *
     * If the column has no explicit tableName, this method returns immediately. If the column's
     * tableName is not among the entity's valid primary/secondary tables, a diagnostic warning is
     * emitted referencing the attribute element, and the column's tableName is replaced with the
     * entity's primary table.
     *
     * @param column    the ColumnModel to validate and potentially modify
     * @param attribute the source AttributeDescriptor used for diagnostic context
     * @param entity    the parent EntityModel that defines valid table names and the primary table
     */
    private void validateAndCorrectTableName(ColumnModel column, AttributeDescriptor attribute, EntityModel entity) {
        String specifiedTable = column.getTableName();

        // If no table is specified, it defaults to the primary table, which is always valid.
        if (specifiedTable == null || specifiedTable.isEmpty()) {
            return;
        }

        // Check if the specified table is a known primary or secondary table for the entity.
        if (!entity.isValidTableName(specifiedTable)) {
            String primaryTable = entity.getTableName();
            context.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                String.format(
                    "Table '%s' specified for column '%s' is not defined as a primary or secondary table for entity '%s'. " +
                    "Falling back to the primary table ('%s').",
                    specifiedTable,
                    attribute.name(),
                    entity.getTableName(),
                    primaryTable
                ),
                attribute.elementForDiagnostics()
            );

            // Fallback: Correct the column to use the primary table.
            column.setTableName(primaryTable);
        }
    }
}