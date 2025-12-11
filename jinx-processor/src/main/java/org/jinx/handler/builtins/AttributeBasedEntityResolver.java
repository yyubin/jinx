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

    public AttributeBasedEntityResolver(ProcessingContext context, SequenceHandler sequenceHandler) {
        this.context = context;
        this.sequenceHandler = sequenceHandler;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    @Override
    public ColumnModel resolve(AttributeDescriptor attribute, TypeMirror typeHint, String pColumnName, Map<String, String> overrides) {
        // Use AttributeDescriptor for type information
        TypeMirror actualType = typeHint != null ? typeHint : attribute.type();

        // Delegate column name resolution to ColumnBuilderFactory, which includes naming strategy
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                attribute, actualType, pColumnName, context, overrides);

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
            try {
                convert.converter();
            } catch (javax.lang.model.type.MirroredTypeException mte) {
                TypeMirror typeMirror = mte.getTypeMirror();
                builder.conversionClass(typeMirror.toString());
            }
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
     * Process @Enumerated attribute with proper default handling and type mapping
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
            builder.enumStringMapping(true);
            int requiredMin = Math.max(255, findMaxEnumNameLength(actualType));
            jakarta.persistence.Column colAnn = attribute.getAnnotation(jakarta.persistence.Column.class);
            int userLen = (colAnn != null) ? colAnn.length() : 255;
            builder.length(Math.max(userLen, requiredMin));
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
     * Check if the given type is an enum
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
     * Extract enum constant names from enum type
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
     * Find the maximum length of enum constant names for VARCHAR sizing
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

    private void processIdGeneration(AttributeDescriptor attribute, ColumnModel.ColumnModelBuilder builder) {
        GeneratedValue generatedValue = attribute.getAnnotation(GeneratedValue.class);
        if (generatedValue == null) return;

        switch (generatedValue.strategy()) {
            case AUTO -> builder.generationStrategy(GenerationStrategy.AUTO);
            case IDENTITY -> builder.generationStrategy(GenerationStrategy.IDENTITY);
            case SEQUENCE -> {
                builder.generationStrategy(GenerationStrategy.SEQUENCE);
                String gen = generatedValue.generator();
                if (gen.isEmpty()) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@GeneratedValue(strategy=SEQUENCE) must specify a 'generator'", attribute.elementForDiagnostics());
                    return;
                }
                builder.sequenceName(gen);
                SequenceGenerator sg = attribute.getAnnotation(SequenceGenerator.class);
                if (sg != null) {
                    sequenceHandler.processSingleGenerator(sg, attribute.elementForDiagnostics());
                }
                validateSequenceGenerator(gen, attribute);
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
     * Validate that the referenced sequence generator exists
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
     * Validate that the referenced table generator exists  
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