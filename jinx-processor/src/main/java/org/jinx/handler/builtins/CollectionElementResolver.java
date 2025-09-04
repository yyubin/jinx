package org.jinx.handler.builtins;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.AbstractColumnResolver;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public class CollectionElementResolver extends AbstractColumnResolver {

    public CollectionElementResolver(ProcessingContext context) {
        super(context);
    }

    @Override
    public ColumnModel resolve(VariableElement field, TypeMirror type, String columnName, Map<String, String> overrides) {
        Column column = field.getAnnotation(Column.class);
        ColumnModel.ColumnModelBuilder builder = ColumnModel.builder()
                .columnName(columnName)
                .javaType(type.toString())
                .isPrimaryKey(false) // 컬렉션 요소는 기본적으로 PK가 아님
                .isNullable(column == null || column.nullable())
                .isUnique(column != null && column.unique())
                .length(column != null ? column.length() : 255)
                .precision(column != null ? column.precision() : 0)
                .scale(column != null ? column.scale() : 0)
                .sqlTypeOverride(column != null && !column.columnDefinition().isEmpty() ? column.columnDefinition() : null)
                .generationStrategy(GenerationStrategy.NONE);

        applyCommonAnnotations(builder, field, type);

        return builder.build();
    }
}