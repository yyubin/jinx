package org.jinx.util;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;
import org.jinx.model.JdbcType;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public class ColumnBuilderFactory {

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

}
