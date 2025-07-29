package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.annotation.Identity;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

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

    public ColumnModel createFrom(VariableElement field, Map<String, String> overrides) {
        Column column = field.getAnnotation(Column.class);
        if (column == null) return null;

        String columnName = overrides.getOrDefault(field.getSimpleName().toString(),
                column.name().isEmpty() ? field.getSimpleName().toString() : column.name());

        ColumnModel.ColumnModelBuilder builder = ColumnModel.builder()
                .columnName(columnName)
                .javaType(field.asType().toString())
                .isPrimaryKey(field.getAnnotation(Id.class) != null)
                .isNullable(column.nullable())
                .isUnique(column.unique())
                .length(column.length())
                .precision(column.precision())
                .scale(column.scale())
                .defaultValue(column.columnDefinition().isEmpty() ? null : column.columnDefinition())
                .generationStrategy(GenerationStrategy.NONE)
                .identityStartValue(1)
                .identityIncrement(1)
                .identityCache(0)
                .identityMinValue(Long.MIN_VALUE)
                .identityMaxValue(Long.MAX_VALUE)
                .identityOptions(new String[]{})
                .enumStringMapping(false)
                .enumValues(new String[]{});

        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated != null) {
            builder.enumStringMapping(enumerated.value() == EnumType.STRING);
            builder.enumValues(getEnumConstants(field.asType()));
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

                    // 필드 레벨 @SequenceGenerator 확인 및 처리
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
        } else if (builder.build().isPrimaryKey()) {
            builder.isManualPrimaryKey(true);
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

