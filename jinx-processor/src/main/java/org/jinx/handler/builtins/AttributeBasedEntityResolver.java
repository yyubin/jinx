package org.jinx.handler.builtins;

import jakarta.persistence.*;
import org.jinx.annotation.Identity;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.AttributeColumnResolver;
import org.jinx.handler.SequenceHandler;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;
import org.jinx.util.ColumnBuilderFactory;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Map;

/**
 * AttributeDescriptor-based column resolver for entity fields
 */
public class AttributeBasedEntityResolver implements AttributeColumnResolver {
    private final ProcessingContext context;
    private final SequenceHandler sequenceHandler;

    /**
     * Create a resolver that derives ColumnModel instances from AttributeDescriptor metadata.
     *
     * Retains the provided ProcessingContext and SequenceHandler for messaging, schema access,
     * converter lookup, and sequence-related operations used during attribute resolution.
     */
    public AttributeBasedEntityResolver(ProcessingContext context, SequenceHandler sequenceHandler) {
        this.context = context;
        this.sequenceHandler = sequenceHandler;
    }

    /**
     * Returns true when the given string is non-null and not empty.
     *
     * <p>Note: this does not treat whitespace-only strings as blank (no trimming is performed).</p>
     *
     * @param s the string to test
     * @return true if s is not null and has length &gt; 0, false otherwise
     */
    private boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * Builds a ColumnModel for the given entity attribute by applying JPA-style
     * attribute annotations and resolver overrides.
     *
     * <p>Resolution behavior:
     * - Determines the effective column name with this priority: explicit
     *   pColumnName, value from overrides map for the attribute, {@code @Column.name()},
     *   then the attribute's own name.
     * - Uses {@code typeHint} when provided; otherwise uses the attribute's declared type.
     * - Applies mapping based on detected annotations: {@code @Basic}, {@code @Version},
     *   {@code @Convert} (field-level or auto-apply converters), {@code @Id}/{@code @EmbeddedId}
     *   (and ID generation via {@code @GeneratedValue}), {@code @Enumerated},
     *   {@code @Temporal} (only for java.util.Date/java.util.Calendar), and a custom
     *   {@code @Identity} that forces IDENTITY generation.
     *
     * <p>Side effects: may emit diagnostic warnings through the processing context
     * (e.g., inappropriate {@code @Temporal} usage or missing sequence/table generator
     * definitions discovered during ID generation processing).
     *
     * @param attribute the attribute descriptor to resolve (provides annotations and element info)
     * @param typeHint optional type to use instead of the attribute's declared type; may be {@code null}
     * @param pColumnName explicit column name override (highest priority); may be {@code null} or blank
     * @param overrides map of attribute-name → column-name overrides used if {@code pColumnName} is not provided
     * @return a fully built ColumnModel reflecting the attribute's mapping and applied overrides
     */
    @Override
    public ColumnModel resolve(AttributeDescriptor attribute, TypeMirror typeHint, String pColumnName, Map<String, String> overrides) {
        Column column = attribute.getAnnotation(Column.class);
        
        // Priority-based column name resolution:
        // 1. Explicit parameter columnName (highest priority)
        // 2. Overrides map for this attribute
        // 3. @Column.name() annotation
        // 4. Attribute name (fallback)
        String columnName = determineColumnName(attribute, pColumnName, column, overrides);
        
        // Use AttributeDescriptor for type information
        TypeMirror actualType = typeHint != null ? typeHint : attribute.type();
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                attribute, actualType, columnName, context, overrides);

        // Handle @Basic
        Basic basic = attribute.getAnnotation(Basic.class);
        if (basic != null) {
            builder.fetchType(basic.fetch());
            builder.isOptional(basic.optional());
        }

        // Handle @Version
        Version version = attribute.getAnnotation(Version.class);
        if (version != null) {
            builder.isVersion(true);
        }

        // Handle @Convert (field-level or autoApply)
        Convert convert = attribute.getAnnotation(Convert.class);
        if (convert != null) {
            String converterClass = convert.converter().getName();
            builder.conversionClass(converterClass);
        } else {
            // Check for autoApply converters
            String targetTypeName = actualType.toString();
            String converterClass = context.getAutoApplyConverters().get(targetTypeName);
            if (converterClass != null) {
                builder.conversionClass(converterClass);
            }
        }

        // Handle @Id and generation
        Id id = attribute.getAnnotation(Id.class);
        if (id != null) {
            builder.isPrimaryKey(true);
            processIdGeneration(attribute, builder);
        }

        // Handle @EmbeddedId
        EmbeddedId embeddedId = attribute.getAnnotation(EmbeddedId.class);
        if (embeddedId != null) {
            builder.isPrimaryKey(true);
        }

        // Handle @Enumerated (with proper default value)
        processEnumeratedAttribute(attribute, actualType, builder);

        // Handle @Temporal
        Temporal temporal = attribute.getAnnotation(Temporal.class);
        if (temporal != null) {
            String typeName = actualType.toString();

            // @Temporal is only for java.util.Date and java.util.Calendar
            if (typeName.startsWith("java.time.")) {
                context.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@Temporal annotation is not applicable to java.time types and will be ignored. " +
                    "Rely on the default JPA mapping for these types.",
                    attribute.elementForDiagnostics());
            } else if (typeName.equals("java.util.Date") || typeName.equals("java.util.Calendar")) {
                builder.temporalType(temporal.value());
            } else {
                context.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@Temporal annotation is only applicable to java.util.Date and java.util.Calendar. It will be ignored for " + typeName + ".",
                    attribute.elementForDiagnostics());
            }
        }

        // Handle custom @Identity
        Identity identity = attribute.getAnnotation(Identity.class);
        if (identity != null) {
            builder.isPrimaryKey(true)
                    .generationStrategy(GenerationStrategy.IDENTITY);
        }

        return builder.build();
    }

    /**
     * Configures the ColumnModel builder for an enum-typed attribute.
     *
     * <p>If the provided type is not an enum, this method does nothing. If the attribute
     * is annotated with {@code @Enumerated}, its value is used; otherwise {@link EnumType#ORDINAL}
     * is the default. The builder's enumeration type is set accordingly. For {@code STRING}
     * mapping the builder is instructed to use string mapping and its length is set to at least
     * 255 or the computed maximum enum-name length (whichever is larger). For {@code ORDINAL}
     * mapping numeric mapping is used. If enum constant names can be extracted, they are supplied
     * to the builder via {@code enumValues}.
     *
     * @param attribute  descriptor of the attribute being processed
     * @param actualType the effective type to inspect (should be an enum DeclaredType)
     * @param builder    the ColumnModel builder to configure
     */
    private void processEnumeratedAttribute(AttributeDescriptor attribute, TypeMirror actualType, ColumnModel.ColumnModelBuilder builder) {
        // Check if this is an enum type
        if (!isEnumType(actualType)) {
            return; // Not an enum, nothing to do
        }

        // Get @Enumerated annotation or use default (ORDINAL)
        Enumerated enumerated = attribute.getAnnotation(Enumerated.class);
        EnumType enumType = enumerated != null ? enumerated.value() : EnumType.ORDINAL;

        // Set enumeration type
        builder.enumerationType(enumType);

        // Set DDL mapping hint based on enum type, but preserve original javaType
        if (enumType == EnumType.STRING) {
            // STRING: Map to VARCHAR with sufficient length
            builder.enumStringMapping(true)
                   .length(Math.max(255, findMaxEnumNameLength(actualType))); // Ensure sufficient length
        } else {
            // ORDINAL: Map to INTEGER
            builder.enumStringMapping(false);
        }

        // Extract enum values for potential use in DDL
        String[] enumValues = extractEnumValues(actualType);
        if (enumValues.length > 0) {
            builder.enumValues(enumValues);
        }
    }

    /**
     * Returns true if the given TypeMirror represents a declared enum type.
     *
     * @return true when {@code typeMirror} is a declared enum TypeElement, false otherwise
     */
    private boolean isEnumType(TypeMirror typeMirror) {
        if (!(typeMirror instanceof javax.lang.model.type.DeclaredType declaredType)) {
            return false;
        }

        Element element = declaredType.asElement();
        return element instanceof javax.lang.model.element.TypeElement typeElement &&
               typeElement.getKind() == javax.lang.model.element.ElementKind.ENUM;
    }

    /**
     * Returns the names of enum constants declared by the given type.
     *
     * If the provided TypeMirror is not a declared enum type, an empty array is returned.
     *
     * @param enumType the type to inspect for enum constants
     * @return an array of enum constant names (may be empty)
     */
    private String[] extractEnumValues(TypeMirror enumType) {
        if (!(enumType instanceof javax.lang.model.type.DeclaredType declaredType)) {
            return new String[0];
        }

        Element element = declaredType.asElement();
        if (!(element instanceof javax.lang.model.element.TypeElement typeElement)) {
            return new String[0];
        }

        return typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == javax.lang.model.element.ElementKind.ENUM_CONSTANT)
                .map(e -> e.getSimpleName().toString())
                .toArray(String[]::new);
    }

    /**
         * Determine a suggested VARCHAR length for an enum based on its constant names.
         *
         * <p>Computes the maximum length of the enum's constant names, enforces a minimum
         * baseline of 50 characters, then adds a buffer (the greater of 10 characters or 20%
         * of the computed maximum) to provide headroom for future changes.</p>
         *
         * @param enumType a TypeMirror representing an enum type
         * @return a suggested VARCHAR length (baseline >= 50) including the buffer
         */
    private int findMaxEnumNameLength(TypeMirror enumType) {
        String[] enumValues = extractEnumValues(enumType);
        int maxLength = 50; // Minimum default

        for (String value : enumValues) {
            maxLength = Math.max(maxLength, value.length());
        }

        // Add some buffer for safety (20% extra or minimum 10 chars)
        return maxLength + Math.max(10, maxLength / 5);
    }

    /**
     * Resolve the target column name for an attribute using a defined priority order.
     *
     * <p>Resolution priority (highest to lowest):
     * <ol>
     *   <li>explicit parameter `pColumnName` (if non-blank)</li>
     *   <li>entry in `overrides` keyed by the attribute name (if non-blank)</li>
     *   <li>`@Column.name()` from the provided `column` annotation (if present and non-blank)</li>
     *   <li>the attribute's own name (fallback)</li>
     * </ol>
     *
     * @param attribute   the attribute descriptor whose name may be used as a fallback
     * @param pColumnName an explicit column name supplied by the caller (highest precedence)
     * @param column      the {@code @Column} annotation instance from the attribute, if any
     * @param overrides   map of attribute-name → column-name overrides to consult before annotation or fallback
     * @return the resolved column name (never null; returns the attribute name if no other source provides a non-blank name)
     */
    private String determineColumnName(AttributeDescriptor attribute, String pColumnName, Column column, Map<String, String> overrides) {
        // Priority 1: Explicit parameter columnName (highest priority)
        if (notBlank(pColumnName)) {
            return pColumnName;
        }
        
        // Priority 2: Overrides map for this attribute  
        String attributeName = attribute.name();
        String overrideName = overrides.get(attributeName);
        if (notBlank(overrideName)) {
            return overrideName;
        }
        
        // Priority 3: @Column.name() annotation
        if (column != null && notBlank(column.name())) {
            return column.name();
        }
        
        // Priority 4: Attribute name (fallback)
        return attributeName;
    }

    /**
     * Applies JPA @GeneratedValue settings from the given attribute to the column builder.
     *
     * <p>If the attribute is annotated with {@code @GeneratedValue}, this method sets the
     * corresponding GenerationStrategy on the builder. For SEQUENCE and TABLE strategies,
     * if a generator name is provided it will be recorded on the builder (sequenceName or
     * tableGeneratorName) and validated with the processing context (which may emit warnings
     * if the referenced generator is not yet known).</p>
     *
     * @param attribute descriptor containing the {@code @GeneratedValue} annotation and source element for diagnostics
     * @param builder   column model builder to receive generation strategy and generator name (if any)
     */
    private void processIdGeneration(AttributeDescriptor attribute, ColumnModel.ColumnModelBuilder builder) {
        GeneratedValue generatedValue = attribute.getAnnotation(GeneratedValue.class);
        if (generatedValue == null) return;

        switch (generatedValue.strategy()) {
            case AUTO -> builder.generationStrategy(GenerationStrategy.AUTO);
            case IDENTITY -> builder.generationStrategy(GenerationStrategy.IDENTITY);
            case SEQUENCE -> {
                builder.generationStrategy(GenerationStrategy.SEQUENCE);
                if (!generatedValue.generator().isEmpty()) {
                    // Use correct ColumnModel field for sequence generator
                    builder.sequenceName(generatedValue.generator());
                    
                    // Verify sequence generator exists and validate
                    validateSequenceGenerator(generatedValue.generator(), attribute);
                }
            }
            case TABLE -> {
                builder.generationStrategy(GenerationStrategy.TABLE);
                if (!generatedValue.generator().isEmpty()) {
                    // Use correct ColumnModel field for table generator
                    builder.tableGeneratorName(generatedValue.generator());
                    
                    // Verify table generator exists and validate
                    validateTableGenerator(generatedValue.generator(), attribute);
                }
            }
        }
    }
    
    /**
     * Ensures a referenced sequence generator is present in the current schema model and emits a warning if not.
     *
     * <p>If the named generator is not found in the processing context's known sequences, a compiler
     * warning is reported referencing the attribute's source element to indicate the generator may be
     * defined in a later compilation round or a different compilation unit.</p>
     *
     * @param generatorName the name of the referenced {@code @SequenceGenerator}
     * @param attribute the attribute whose declaration references the sequence (used for diagnostic location)
     */
    private void validateSequenceGenerator(String generatorName, AttributeDescriptor attribute) {
        if (!context.getSchemaModel().getSequences().containsKey(generatorName)) {
            // Issue warning - generator might be defined later or in another compilation unit
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                    "Referenced @SequenceGenerator '" + generatorName + "' not found in the current processing round. " +
                    "This may be resolved in a subsequent round.",
                    attribute.elementForDiagnostics());
        }
    }
    
    /**
     * Verifies that a referenced @TableGenerator is present in the current schema model
     * and emits a compiler warning if it is not found.
     *
     * <p>If the named generator is missing from {@code context.getSchemaModel().getTableGenerators()},
     * a {@link javax.tools.Diagnostic.Kind#WARNING} is printed for the element represented by
     * {@code attribute} to inform the user that the generator may be defined in a later
     * compilation round or a different compilation unit.</p>
     *
     * @param generatorName the name of the referenced {@code @TableGenerator}
     * @param attribute     the attribute whose declaration is the source of the reference;
     *                      used to provide location information for the warning
     */
    private void validateTableGenerator(String generatorName, AttributeDescriptor attribute) {
        if (!context.getSchemaModel().getTableGenerators().containsKey(generatorName)) {
            // Issue warning - generator might be defined later or in another compilation unit
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                    "Referenced @TableGenerator '" + generatorName + "' not found. " +
                    "Make sure it's defined in the same compilation unit.",
                    attribute.elementForDiagnostics());
        }
    }
}