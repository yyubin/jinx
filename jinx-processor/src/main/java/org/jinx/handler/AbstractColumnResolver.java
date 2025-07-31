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

public abstract class AbstractColumnResolver implements ColumnResolver {
    protected final ProcessingContext context;

    protected AbstractColumnResolver(ProcessingContext context) {
        this.context = context;
    }

    protected void applyCommonAnnotations(ColumnModel.ColumnModelBuilder builder, VariableElement field, TypeMirror type) {
        // @Lob 처리
        if (field.getAnnotation(Lob.class) != null) {
            builder.isLob(true);
            String javaType = type.toString();
            if (javaType.equals("java.lang.String")) {
                builder.jdbcType(JdbcType.CLOB);
            } else {
                builder.jdbcType(JdbcType.BLOB);
            }
        }

        // @Enumerated 처리
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated != null) {
            boolean isStringMapping = enumerated.value() == EnumType.STRING;
            builder.enumStringMapping(isStringMapping);
            if (isStringMapping) {
                builder.enumValues(ColumnUtils.getEnumConstants(type));
            }
        }

        // @Temporal 처리
        Temporal temporal = field.getAnnotation(Temporal.class);
        if (temporal != null) {
            builder.temporalType(temporal.value());
        }
    }
}
