package org.jinx.handler;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EmbeddedHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;

    public EmbeddedHandler(ProcessingContext context, ColumnHandler columnHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
    }

    public void processEmbedded(VariableElement field, Map<String, ColumnModel> columns, List<ConstraintModel> constraints, Set<String> processedTypes) {
        TypeMirror typeMirror = field.asType();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        AttributeOverrides overrides = field.getAnnotation(AttributeOverrides.class);
        Map<String, String> columnOverrides = new HashMap<>();
        if (overrides != null) {
            for (AttributeOverride override : overrides.value()) {
                columnOverrides.put(override.name(), override.column().name());
            }
        }

        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement embeddedField = (VariableElement) enclosed;
            if (embeddedField.getAnnotation(Embedded.class) != null) {
                processEmbedded(embeddedField, columns, constraints, processedTypes);
            } else {
                ColumnModel column = columnHandler.createFrom(embeddedField, columnOverrides);
                if (column != null) {
                    columns.putIfAbsent(column.getColumnName(), column);
                }
            }
        }

        processedTypes.remove(typeName);
    }
}
