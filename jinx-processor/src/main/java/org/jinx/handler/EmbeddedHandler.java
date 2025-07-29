package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.*;
import java.util.stream.Collectors;

public class EmbeddedHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;

    public EmbeddedHandler(ProcessingContext context, ColumnHandler columnHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
    }

    public void processEmbedded(VariableElement field, Map<String, ColumnModel> columns, List<RelationshipModel> constraints, Set<String> processedTypes) {
        TypeMirror typeMirror = field.asType();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        Map<String, String> columnOverrides = new HashMap<>();
        Map<String, JoinColumn> associationOverrides = new HashMap<>();
        AttributeOverrides attrOverrides = field.getAnnotation(AttributeOverrides.class);
        AttributeOverride singleAttrOverride = field.getAnnotation(AttributeOverride.class);
        AssociationOverrides assocOverrides = field.getAnnotation(AssociationOverrides.class);
        AssociationOverride singleAssocOverride = field.getAnnotation(AssociationOverride.class);

        if (attrOverrides != null) {
            for (AttributeOverride override : attrOverrides.value()) {
                columnOverrides.put(override.name(), override.column().name());
            }
        }
        if (singleAttrOverride != null) {
            columnOverrides.put(singleAttrOverride.name(), singleAttrOverride.column().name());
        }
        if (assocOverrides != null) {
            for (AssociationOverride override : assocOverrides.value()) {
                associationOverrides.put(override.name(), override.joinColumns()[0]);
            }
        }
        if (singleAssocOverride != null) {
            associationOverrides.put(singleAssocOverride.name(), singleAssocOverride.joinColumns()[0]);
        }

        boolean isElementCollection = field.getAnnotation(ElementCollection.class) != null;
        String prefix = isElementCollection ? field.getSimpleName().toString() + "_" : "";

        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement embeddedField = (VariableElement) enclosed;
            if (embeddedField.getAnnotation(Embedded.class) != null) {
                processEmbedded(embeddedField, columns, constraints, processedTypes);
            } else if (embeddedField.getAnnotation(ManyToOne.class) != null || embeddedField.getAnnotation(OneToOne.class) != null) {
                processEmbeddedRelationship(embeddedField, columns, constraints, associationOverrides, prefix);
            } else {
                ColumnModel column = columnHandler.createFrom(embeddedField, columnOverrides);
                if (column != null) {
                    String columnName = prefix + column.getColumnName();
                    column.setColumnName(columnName);
                    columns.putIfAbsent(columnName, column);
                }
            }
        }

        processedTypes.remove(typeName);
    }

    private void processEmbeddedRelationship(VariableElement field, Map<String, ColumnModel> columns, List<RelationshipModel> relationshipModels, Map<String, JoinColumn> associationOverrides, String prefix) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        JoinColumn joinColumn = associationOverrides.getOrDefault(field.getSimpleName().toString(), field.getAnnotation(JoinColumn.class));
        if (joinColumn == null) return;

        TypeElement referencedTypeElement = (TypeElement) ((DeclaredType) field.asType()).asElement();
        EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;
        Optional<String> referencedPkColumnName = context.findPrimaryKeyColumnName(referencedEntity);
        if (referencedPkColumnName.isEmpty()) return;

        ColumnModel referencedPkColumn = referencedEntity.getColumns().get(referencedPkColumnName.get());
        String fkColumnName = joinColumn.name().isEmpty() ? prefix + field.getSimpleName().toString() + "_" + referencedPkColumnName.get() : prefix + joinColumn.name();
        MapsId mapsId = field.getAnnotation(MapsId.class);

        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(referencedPkColumn.getJavaType())
                .isPrimaryKey(mapsId != null)
                .isNullable(mapsId == null && (manyToOne != null ? manyToOne.optional() : oneToOne != null && oneToOne.optional()))
                .isUnique(oneToOne != null && mapsId == null)
                .generationStrategy(GenerationStrategy.NONE)
                .build();
        columns.putIfAbsent(fkColumnName, fkColumn);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .column(fkColumnName)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumn(referencedPkColumnName.get())
                .mapsId(mapsId != null)
                .constraintName(joinColumn.name().isEmpty() ? "fk_" + fkColumnName : joinColumn.name())
                .cascadeTypes(manyToOne != null ? Arrays.stream(manyToOne.cascade()).map(c -> CascadeType.valueOf(c.name())).collect(Collectors.toList())
                        : Arrays.stream(oneToOne.cascade()).map(c -> CascadeType.valueOf(c.name())).collect(Collectors.toList()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? FetchType.valueOf(manyToOne.fetch().name()) : FetchType.valueOf(oneToOne.fetch().name()))
                .build();
        relationshipModels.add(relationship);
    }
}