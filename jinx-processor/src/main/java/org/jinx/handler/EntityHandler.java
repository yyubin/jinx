package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.*;

public class EntityHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;
    private final ConstraintHandler constraintHandler;
    private final SequenceHandler sequenceHandler;

    public EntityHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler, ConstraintHandler constraintHandler, SequenceHandler sequenceHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.constraintHandler = constraintHandler;
        this.sequenceHandler = sequenceHandler;
    }

    public void handle(TypeElement typeElement) {
        Table table = typeElement.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isEmpty() ? table.name() : typeElement.getSimpleName().toString();
        String entityName = typeElement.getQualifiedName().toString();

        if (context.getSchemaModel().getEntities().containsKey(entityName)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Duplicate entity found: " + entityName, typeElement);
            EntityModel invalidEntity = EntityModel.builder().entityName(entityName).isValid(false).build();
            context.getSchemaModel().getEntities().putIfAbsent(entityName, invalidEntity);
            return;
        }

        EntityModel entity = EntityModel.builder()
                .entityName(entityName)
                .tableName(tableName)
                .isValid(true)
                .build();

        sequenceHandler.processSequenceGenerators(typeElement);
        processConstraints(typeElement, null, entity.getConstraints());

        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getAnnotation(Transient.class) != null ||
                    enclosed.getModifiers().contains(Modifier.TRANSIENT) ||
                    enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            if (field.getAnnotation(Embedded.class) != null) {
                embeddedHandler.processEmbedded(field, entity.getColumns(), entity.getConstraints(), new HashSet<>());
            } else {
                ColumnModel column = columnHandler.createFrom(field, Collections.emptyMap());
                if (column != null) {
                    entity.getColumns().putIfAbsent(column.getColumnName(), column);
                }
            }
        }

        if (table != null) {
            for (Index index : table.indexes()) {
                String indexName = index.name();
                if (entity.getIndexes().containsKey(indexName)) {
                    context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate index name '" + indexName + "' in entity " + entityName, typeElement);
                    continue;
                }
                IndexModel indexModel = IndexModel.builder()
                        .indexName(indexName)
                        .columnNames(Arrays.asList(index.columnList().split(",\\s*")))
                        .isUnique(index.unique())
                        .build();
                entity.getIndexes().put(indexModel.getIndexName(), indexModel);
            }
        }

        context.getSchemaModel().getEntities().putIfAbsent(entity.getEntityName(), entity);
    }

    private void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints) {
        constraintHandler.processConstraints(element, fieldName, constraints);
    }
}