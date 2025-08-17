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
            for (AssociationOverride ao : assocOverrides.value()) {
                JoinColumn[] jcs = ao.joinColumns();
                if (jcs != null && jcs.length > 0) {
                    associationOverrides.put(ao.name(), jcs[0]);
                }
            }
        }
        if (singleAssocOverride != null) {
            JoinColumn[] jcs = singleAssocOverride.joinColumns();
            if (jcs != null && jcs.length > 0) {
                associationOverrides.put(singleAssocOverride.name(), jcs[0]);
            }
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
        JoinColumn joinColumn =
                associationOverrides.getOrDefault(field.getSimpleName().toString(),
                        field.getAnnotation(JoinColumn.class));
        String rawName = (joinColumn == null || joinColumn.name() == null)
                ? ""
                : joinColumn.name();

        TypeElement referencedTypeElement = (TypeElement) ((DeclaredType) field.asType()).asElement();
        EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;
        Optional<String> referencedPkColumnName = context.findPrimaryKeyColumnName(referencedEntity);
        if (referencedPkColumnName.isEmpty()) return;

        // FIX : 묵시적 JoinColumn 케이스 지원
        String fkColumnName = (rawName == null || rawName.isEmpty())
                ? prefix + field.getSimpleName() + "_" + referencedPkColumnName.get()
                : prefix + rawName;

        ColumnModel referencedPkColumn = referencedEntity.getColumns().get(referencedPkColumnName.get());
        MapsId mapsId = field.getAnnotation(MapsId.class);

        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(referencedPkColumn.getJavaType())
                .isPrimaryKey(mapsId != null)
                .isNullable(mapsId == null &&
                        (manyToOne != null ? manyToOne.optional()
                                : oneToOne != null && oneToOne.optional()))
                .isUnique(oneToOne != null && mapsId == null)
                .generationStrategy(GenerationStrategy.NONE)
                .build();
        columns.putIfAbsent(fkColumnName, fkColumn);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .columns(List.of(fkColumnName))
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(List.of(referencedPkColumnName.get()))
                .mapsId(mapsId != null)
                .constraintName(rawName == null || rawName.isEmpty()
                        ? "fk_" + fkColumnName
                        : rawName)
                .cascadeTypes(manyToOne != null
                        ? toCascadeList(manyToOne.cascade())
                        : toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null
                        ? safeFetch(manyToOne.fetch(), FetchType.LAZY)
                        : safeFetch(oneToOne.fetch(), FetchType.LAZY))
                .build();
        relationshipModels.add(relationship);
    }

    public void processEmbeddableFields(TypeElement embeddableType, Map<String, ColumnModel> columns,
                                        List<RelationshipModel> relationships, Set<String> processedTypes,
                                        String prefix, VariableElement collectionField) {
        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        String effectivePrefix = (prefix != null ? prefix : "") + (collectionField != null ? collectionField.getSimpleName() + "_" : "");

        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement embeddedField = (VariableElement) enclosed;

            if (embeddedField.getAnnotation(Embedded.class) != null) {
                processEmbedded(embeddedField, columns, relationships, processedTypes);
            } else if (embeddedField.getAnnotation(ManyToOne.class) != null || embeddedField.getAnnotation(OneToOne.class) != null) {
                processEmbeddedRelationship(embeddedField, columns, relationships, new HashMap<>(), effectivePrefix);
            } else {
                // 컬렉션 필드의 어노테이션을 참조하여 ColumnModel 생성
                Map<String, String> overrides = new HashMap<>();
                if (collectionField != null) {
                    AttributeOverride attrOverride = collectionField.getAnnotation(AttributeOverride.class);
                    if (attrOverride != null && attrOverride.name().equals(embeddedField.getSimpleName().toString())) {
                        overrides.put(embeddedField.getSimpleName().toString(), attrOverride.column().name());
                    }
                }
                ColumnModel column = columnHandler.createFrom(embeddedField, overrides);
                if (column != null) {
                    String columnName = effectivePrefix + column.getColumnName();
                    column.setColumnName(columnName);
                    columns.putIfAbsent(columnName, column);
                }
            }
        }

        processedTypes.remove(typeName);
    }

    private static List<CascadeType> toCascadeList(CascadeType[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).toList();
    }

    private static FetchType safeFetch(FetchType ft, FetchType def) {
        return ft == null ? def : ft;
    }

}