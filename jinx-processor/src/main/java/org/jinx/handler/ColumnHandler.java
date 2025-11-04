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

/**
 * Handles the creation of {@link ColumnModel} instances from various sources.
 * <p>
 * This class acts as a facade, delegating the actual column resolution logic
 * to a set of specialized {@link ColumnResolver} and {@link AttributeColumnResolver}
 * implementations. It also provides validation for table names.
 */
public class ColumnHandler {
    private final ProcessingContext context;
    private final SequenceHandler sequenceHandler;
    private final ColumnResolver fieldBasedResolver;
    private final ColumnResolver typeBasedResolver;
    private final AttributeColumnResolver attributeBasedResolver;

    public ColumnHandler(ProcessingContext context, SequenceHandler sequenceHandler) {
        this.context = context;
        this.sequenceHandler = sequenceHandler;
        this.fieldBasedResolver = new CollectionElementResolver(context);
        this.typeBasedResolver = new EntityFieldResolver(context, sequenceHandler);
        this.attributeBasedResolver = new AttributeBasedEntityResolver(context, sequenceHandler);
    }

    /**
     * Creates a ColumnModel from a field element, considering overrides.
     *
     * @param field The field element.
     * @param overrides A map of property overrides.
     * @return A new ColumnModel instance.
     */
    public ColumnModel createFrom(VariableElement field, Map<String, String> overrides) {
        return typeBasedResolver.resolve(field, null, null, overrides);
    }

    /**
     * Creates a ColumnModel from a field's type with a specific column name.
     *
     * @param field The field element.
     * @param type The type of the column.
     * @param columnName The explicit name for the column.
     * @return A new ColumnModel instance.
     */
    public ColumnModel createFromFieldType(VariableElement field, TypeMirror type, String columnName) {
        return fieldBasedResolver.resolve(field, type, columnName, Map.of());
    }

    /**
     * Creates a ColumnModel from an attribute descriptor, applying overrides and validating the table name.
     *
     * @param attribute The attribute descriptor.
     * @param entity The owning entity model.
     * @param overrides A map of property overrides.
     * @return A new, validated ColumnModel instance.
     */
    public ColumnModel createFromAttribute(AttributeDescriptor attribute, EntityModel entity, Map<String, String> overrides) {
        ColumnModel column = attributeBasedResolver.resolve(attribute, null, null, overrides);
        if (column == null) {
            return null;
        }
        String tableOverride = overrides.get("tableName");
        if (tableOverride != null && !tableOverride.isEmpty()) {
            column.setTableName(tableOverride);
        }
        validateAndCorrectTableName(column, attribute, entity);
        return column;
    }

    /**
     * Creates a ColumnModel from an attribute's type with a specific column name and validates the table name.
     *
     * @param attribute The attribute descriptor.
     * @param entity The owning entity model.
     * @param type The type of the column.
     * @param columnName The explicit name for the column.
     * @return A new, validated ColumnModel instance.
     */
    public ColumnModel createFromAttributeType(AttributeDescriptor attribute, EntityModel entity, TypeMirror type, String columnName) {
        ColumnModel column = attributeBasedResolver.resolve(attribute, type, columnName, Map.of());
        if (column == null) {
            return null;
        }
        validateAndCorrectTableName(column, attribute, entity);
        return column;
    }

    /**
     * Validates the table name specified in a ColumnModel against the parent EntityModel.
     * If the table name is invalid, it prints a warning and sets the column's table
     * to the entity's primary table.
     *
     * @param column The resolved ColumnModel to validate.
     * @param attribute The source AttributeDescriptor for context in error messages.
     * @param entity The parent EntityModel containing the list of valid table names.
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