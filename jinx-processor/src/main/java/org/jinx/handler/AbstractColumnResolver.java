package org.jinx.handler;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Temporal;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.JdbcType;
import org.jinx.util.ColumnUtils;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * An abstract base class for {@link ColumnResolver} implementations.
 * <p>
 * This class provides common functionality for processing standard JPA annotations
 * like {@code @Lob}, {@code @Enumerated}, and {@code @Temporal} that affect column definitions.
 */
public abstract class AbstractColumnResolver implements ColumnResolver {
    protected final ProcessingContext context;

    protected AbstractColumnResolver(ProcessingContext context) {
        this.context = context;
    }

    /**
     * Applies common JPA annotations to a {@link ColumnModel.ColumnModelBuilder}.
     *
     * @param builder The column model builder to configure.
     * @param field The field element being processed.
     * @param type The type of the field.
     */
    protected void applyCommonAnnotations(ColumnModel.ColumnModelBuilder builder, VariableElement field, TypeMirror type) {
        // Handle @Lob
        if (field.getAnnotation(Lob.class) != null) {
            builder.isLob(true);
            String javaType = type.toString();
            if (javaType.equals("java.lang.String")) {
                builder.jdbcType(JdbcType.CLOB);
            } else {
                builder.jdbcType(JdbcType.BLOB);
            }
        }

        // Handle @Enumerated
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated != null) {
            boolean isStringMapping = enumerated.value() == EnumType.STRING;
            builder.enumStringMapping(isStringMapping);
            if (isStringMapping) {
                builder.enumValues(ColumnUtils.getEnumConstants(type));
            }
        }

        // Handle @Temporal
        Temporal temporal = field.getAnnotation(Temporal.class);
        if (temporal != null) {
            builder.temporalType(temporal.value());
        }
    }
}
