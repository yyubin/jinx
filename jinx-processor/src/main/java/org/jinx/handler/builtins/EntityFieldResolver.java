package org.jinx.handler.builtins;

import jakarta.persistence.*;
import org.jinx.annotation.Identity;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.AbstractColumnResolver;
import org.jinx.handler.ColumnResolver;
import org.jinx.handler.SequenceHandler;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;
import org.jinx.model.JdbcType;
import org.jinx.util.ColumnBuilderFactory;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public class EntityFieldResolver extends AbstractColumnResolver {
    private final SequenceHandler sequenceHandler;

    public EntityFieldResolver(ProcessingContext context, SequenceHandler sequenceHandler) {
        super(context);
        this.sequenceHandler = sequenceHandler;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    @Override
    public ColumnModel resolve(VariableElement field, TypeMirror typeHint, String pColumnName, Map<String, String> overrides) {
        Column column = field.getAnnotation(Column.class);
        String columnName = overrides.getOrDefault(
                field.getSimpleName().toString(),
                column != null && notBlank(column.name()) ? column.name() : field.getSimpleName().toString());
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(field, typeHint, columnName, context, overrides);

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

        applyCommonAnnotations(builder, field, typeHint);

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
}