package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.annotation.Identity;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public class ColumnHandler {
    private final ProcessingContext context;
    private final SequenceHandler sequenceHandler;

    public ColumnHandler(ProcessingContext context, SequenceHandler sequenceHandler) {
        this.context = context;
        this.sequenceHandler = sequenceHandler;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    public ColumnModel createFrom(VariableElement field, Map<String, String> overrides) {
        Column column = field.getAnnotation(Column.class);
        String columnName = overrides.getOrDefault(
                field.getSimpleName().toString(),
                column != null && notBlank(column.name()) ? column.name() : field.getSimpleName().toString());

        ColumnModel.ColumnModelBuilder builder = ColumnModel.builder()
                .columnName(columnName)
                .javaType(field.asType().toString())
                .isPrimaryKey(field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null)
                .isNullable(column == null || column.nullable())
                .isUnique(column != null && column.unique())
                .length(column != null ? column.length() : 255)
                .precision(column != null ? column.precision() : 0)
                .scale(column != null ? column.scale() : 0)
                .defaultValue(column != null && !column.columnDefinition().isEmpty() ? column.columnDefinition() : null)
                .generationStrategy(GenerationStrategy.NONE)
                .identityStartValue(1)
                .identityIncrement(1)
                .identityCache(0)
                .identityMinValue(Long.MIN_VALUE)
                .identityMaxValue(Long.MAX_VALUE)
                .identityOptions(new String[]{})
                .enumStringMapping(false)
                .enumValues(new String[]{})
                .isLob(false)
                .isOptional(column != null ? column.nullable() : true)
                .isVersion(false)
                .isManualPrimaryKey(field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null);

        // Handle @Lob
        Lob lob = field.getAnnotation(Lob.class);
        if (lob != null) {
            builder.isLob(true);
            String javaType = field.asType().toString();
            if (javaType.equals("java.lang.String") || javaType.equals("java.sql.Clob") || javaType.equals("java.sql.NClob")) {
                builder.jdbcType(JdbcType.CLOB);
            } else if (javaType.equals("[B") || javaType.equals("java.sql.Blob") || javaType.equals("[Ljava.lang.Byte;")) {
                builder.jdbcType(JdbcType.BLOB);
            }
        }

        // Handle @Basic
        Basic basic = field.getAnnotation(Basic.class);
        if (basic != null) {
            builder.fetchType(basic.fetch());
            builder.isOptional(basic.optional());
        }

        // Handle @Version
        Version version = field.getAnnotation(Version.class);
        if (version != null) {
            builder.isVersion(true);
        }

        // Handle @Convert (field-level or autoApply)
        Convert convert = field.getAnnotation(Convert.class);
        if (convert != null) {
            builder.conversionClass(convert.converter().getName());
        } else {
            String autoApplyConverter = context.getAutoApplyConverters().get(field.asType().toString());
            if (autoApplyConverter != null) {
                builder.conversionClass(autoApplyConverter);
            }
        }

        // Handle @Enumerated
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated != null) {
            builder.enumStringMapping(enumerated.value() == EnumType.STRING);
            if (builder.build().isEnumStringMapping()) {
                builder.enumValues(getEnumConstants(field.asType()));
            }
        }

        // Handle @Temporal
        Temporal temporal = field.getAnnotation(Temporal.class);
        if (temporal != null) {
            builder.temporalType(temporal.value());
        }

        Identity identity = field.getAnnotation(Identity.class);
        GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
        if (identity != null) {
            builder.generationStrategy(GenerationStrategy.IDENTITY)
                    .identityStartValue(identity.start())
                    .identityIncrement(identity.increment())
                    .identityCache(identity.cache())
                    .identityMinValue(identity.min())
                    .identityMaxValue(identity.max())
                    .identityOptions(identity.options());
        } else if (gv != null) {
            switch (gv.strategy()) {
                case IDENTITY, AUTO:
                    builder.generationStrategy(GenerationStrategy.IDENTITY);
                    break;
                case SEQUENCE:
                    builder.generationStrategy(GenerationStrategy.SEQUENCE);
                    String generatorName = gv.generator();
                    if (generatorName.isBlank()) {
                        context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                "@GeneratedValue(strategy=SEQUENCE) must specify a 'generator'", field);
                        return null;
                    }
                    builder.sequenceName(generatorName);
                    SequenceGenerator sg = field.getAnnotation(SequenceGenerator.class);
                    if (sg != null) {
                        sequenceHandler.processSingleGenerator(sg, field);
                    }
                    break;
                case TABLE:
                    builder.generationStrategy(GenerationStrategy.TABLE);
                    builder.tableGeneratorName(gv.generator());
                    break;
            }
        }

        return builder.build();
    }

    private String[] getEnumConstants(TypeMirror tm) {
        if (!(tm instanceof DeclaredType dt)) return new String[]{};
        Element e = dt.asElement();
        if (e.getKind() != ElementKind.ENUM) return new String[]{};
        return e.getEnclosedElements().stream()
                .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
                .map(Element::getSimpleName)
                .map(Object::toString)
                .toArray(String[]::new);
    }
}