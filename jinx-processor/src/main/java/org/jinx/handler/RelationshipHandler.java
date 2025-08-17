package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

public class RelationshipHandler {
    private final ProcessingContext context;

    public RelationshipHandler(ProcessingContext context) {
        this.context = context;
    }

    public void resolveRelationships(TypeElement typeElement, EntityModel entityModel) {
        for (Element field : typeElement.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD ||
                    field.getAnnotation(Transient.class) != null ||
                    field.getModifiers().contains(Modifier.TRANSIENT)) {
                continue;
            }
            VariableElement variableField = (VariableElement) field;
            resolveFieldRelationships(variableField, entityModel);
        }
    }

    private void resolveFieldRelationships(VariableElement field, EntityModel ownerEntity) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
        OneToMany oneToMany = field.getAnnotation(OneToMany.class);

        if (manyToOne != null || oneToOne != null) {
            processToOneRelationship(field, ownerEntity, manyToOne, oneToOne);
        } else if (oneToMany != null) {
            if (!oneToMany.mappedBy().isEmpty()) {
                return; // Skip if mappedBy is set (inverse side)
            }
            processOneToMany(field, ownerEntity, oneToMany);
        } else if (manyToMany != null) {
            if (!manyToMany.mappedBy().isEmpty()) {
                return; // Skip if mappedBy is set (inverse side)
            }
            processManyToMany(field, ownerEntity, manyToMany);
        }
    }

    private void processToOneRelationship(VariableElement field, EntityModel ownerEntity, ManyToOne manyToOne, OneToOne oneToOne) {
        JoinColumns joinColumnsAnno = field.getAnnotation(JoinColumns.class);
        JoinColumn joinColumnAnno = field.getAnnotation(JoinColumn.class);

        List<JoinColumn> joinColumns;
        if (joinColumnsAnno != null) {
            joinColumns = Arrays.asList(joinColumnsAnno.value());
        } else if (joinColumnAnno != null) {
            joinColumns = List.of(joinColumnAnno);
        } else {
            return;
        }

        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
        if (referencedTypeElement == null) return;
        EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        Map<String, ColumnModel> referencedPkColumns = referencedEntity.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c));

        if (referencedPkColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have primary key(s) (@Id).", field);
            referencedEntity.setValid(false);
            return;
        }

        if (joinColumns.size() != referencedPkColumns.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinColumns size must match the number of primary keys in referenced entity.", field);
            return;
        }

        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNamesInOrder = new ArrayList<>();
        List<String> constraintNames = new ArrayList<>();
        MapsId mapsId = field.getAnnotation(MapsId.class);

        for (JoinColumn jc : joinColumns) {
            String referencedPkName = jc.referencedColumnName();

            if (referencedPkName.isEmpty()) {
                if (referencedPkColumns.size() == 1) {
                    referencedPkName = referencedPkColumns.keySet().iterator().next();
                } else {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "referencedColumnName must be specified for composite foreign keys.", field);
                    return;
                }
            }

            ColumnModel referencedPkColumn = referencedPkColumns.get(referencedPkName);
            if (referencedPkColumn == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "referencedColumnName '" + referencedPkName + "' not found in " + referencedEntity.getEntityName(), field);
                return;
            }

            String fkColumnName = jc.name().isEmpty() ? field.getSimpleName().toString() + "_" + referencedPkName : jc.name();

            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .javaType(referencedPkColumn.getJavaType())
                    .isPrimaryKey(mapsId != null)
                    .isNullable(mapsId == null && (manyToOne != null ? manyToOne.optional() : oneToOne != null && oneToOne.optional()))
                    .isUnique(oneToOne != null && mapsId == null && joinColumns.size() == 1)  // 복합 시 unique는 전체에 적용되지 않음
                    .generationStrategy(GenerationStrategy.NONE)
                    .build();
            if (ownerEntity.getColumns().containsKey(fkColumnName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate column name '" + fkColumnName + "' in entity " + ownerEntity.getEntityName(), field);
                return;
            }
            ownerEntity.getColumns().putIfAbsent(fkColumnName, fkColumn);

            fkColumnNames.add(fkColumnName);
            referencedPkNamesInOrder.add(referencedPkName);
            constraintNames.add(jc.name().isEmpty() ? "fk_" + fkColumnName : jc.name());
        }

        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNamesInOrder)
                .mapsId(mapsId != null)
                .constraintName(String.join("_", constraintNames))  // 복합 키 이름 합침
                .cascadeTypes(manyToOne != null ? Arrays.stream(manyToOne.cascade()).map(c -> CascadeType.valueOf(c.name())).collect(Collectors.toList())
                        : Arrays.stream(oneToOne.cascade()).map(c -> CascadeType.valueOf(c.name())).collect(Collectors.toList()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? FetchType.valueOf(manyToOne.fetch().name()) : FetchType.valueOf(oneToOne.fetch().name()))
                .build();
        ownerEntity.getRelationships().add(relationship);
    }

    private void processOneToMany(VariableElement field, EntityModel ownerEntity, OneToMany oneToMany) {
        TypeMirror targetType = ((DeclaredType) field.asType()).getTypeArguments().get(0);
        TypeElement targetEntity = (TypeElement) ((DeclaredType) targetType).asElement();
        EntityModel targetEntityModel = context.getSchemaModel().getEntities().get(targetEntity.getQualifiedName().toString());
        if (targetEntityModel == null) return;

        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        String columnName = joinColumn != null && !joinColumn.name().isEmpty() ? joinColumn.name() : ownerEntity.getTableName() + "_id";

        RelationshipModel relationship = RelationshipModel.builder()
                .type(RelationshipType.ONE_TO_MANY)
                .columns(List.of(columnName))
                .referencedTable(targetEntityModel.getTableName())
                .referencedColumns(List.of(columnName))
                .constraintName("fk_" + targetEntityModel.getTableName() + "_" + columnName)
                .cascadeTypes(Arrays.stream(oneToMany.cascade()).map(c -> CascadeType.valueOf(c.name())).collect(Collectors.toList()))
                .orphanRemoval(oneToMany.orphanRemoval())
                .fetchType(FetchType.valueOf(oneToMany.fetch().name()))
                .build();
        ownerEntity.getRelationships().add(relationship);
    }

    private void processManyToMany(VariableElement field, EntityModel ownerEntity, ManyToMany manyToMany) {
        JoinTableDetails details = getJoinTableDetails(field, ownerEntity);
        if (details == null) return;

        if (context.getSchemaModel().getEntities().containsKey(details.joinTableName)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Duplicate join table entity '" + details.joinTableName + "'", field);
            return;
        }

        EntityModel joinTableEntity = createJoinTableEntity(details);
        addRelationshipsToJoinTable(joinTableEntity, details, manyToMany);

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private JoinTableDetails getJoinTableDetails(VariableElement field, EntityModel ownerEntity) {
        JoinTable joinTable = field.getAnnotation(JoinTable.class);
        TypeMirror targetType = ((DeclaredType) field.asType()).getTypeArguments().get(0);
        TypeElement referencedTypeElement = (TypeElement) ((DeclaredType) targetType).asElement();
        if (referencedTypeElement == null) return null;
        EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return null;

        String ownerTable = ownerEntity.getTableName();
        String referencedTable = referencedEntity.getTableName();
        Optional<String> ownerPkOpt = context.findPrimaryKeyColumnName(ownerEntity);
        Optional<String> inversePkOpt = context.findPrimaryKeyColumnName(referencedEntity);
        if (ownerPkOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + ownerEntity.getEntityName() + " must have a primary key (@Id).");
            ownerEntity.setValid(false);
            return null;
        }
        if (inversePkOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have a primary key (@Id).");
            referencedEntity.setValid(false);
            return null;
        }

        String ownerPkColumnName = ownerPkOpt.get();
        String inversePkColumnName = inversePkOpt.get();

        String joinTableName;
        if (joinTable != null && !joinTable.name().isEmpty()) {
            joinTableName = joinTable.name();
        } else {
            List<String> tableNames = Arrays.asList(ownerTable.toLowerCase(), referencedTable.toLowerCase());
            Collections.sort(tableNames);
            joinTableName = String.join("_", tableNames);
        }

        String ownerFkColumn = joinTable != null && joinTable.joinColumns().length > 0 && !joinTable.joinColumns()[0].name().isEmpty()
                ? joinTable.joinColumns()[0].name() : ownerTable.toLowerCase() + "_" + ownerPkOpt.get();
        String inverseFkColumn = joinTable != null && joinTable.inverseJoinColumns().length > 0 && !joinTable.inverseJoinColumns()[0].name().isEmpty()
                ? joinTable.inverseJoinColumns()[0].name() : referencedTable.toLowerCase() + "_" + inversePkOpt.get();

        return new JoinTableDetails(joinTableName, ownerFkColumn, inverseFkColumn, ownerPkColumnName, inversePkColumnName, ownerEntity, referencedEntity);
    }

    private EntityModel createJoinTableEntity(JoinTableDetails details) {
        EntityModel joinTableEntity = EntityModel.builder()
                .entityName(details.joinTableName)
                .tableName(details.joinTableName)
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();

        ColumnModel ownerFkColumnModel = ColumnModel.builder()
                .columnName(details.ownerFkColumn)
                .javaType(details.ownerEntity.getColumns().get(details.ownerPkColumnName).getJavaType())
                .isPrimaryKey(true)
                .build();
        joinTableEntity.getColumns().put(details.ownerFkColumn, ownerFkColumnModel);

        ColumnModel inverseFkColumnModel = ColumnModel.builder()
                .columnName(details.inverseFkColumn)
                .javaType(details.referencedEntity.getColumns().get(details.inversePkColumnName).getJavaType())
                .isPrimaryKey(true)
                .build();
        joinTableEntity.getColumns().put(details.inverseFkColumn, inverseFkColumnModel);

        return joinTableEntity;
    }

    private List<CascadeType> toCascadeList(CascadeType[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).toList();
    }

    private FetchType safeFetch(FetchType ft, FetchType defaultFt) {
        return ft == null ? defaultFt : ft;
    }

    private void addRelationshipsToJoinTable(EntityModel joinTableEntity, JoinTableDetails details, ManyToMany manyToMany) {
        RelationshipModel ownerRelationship = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_MANY)
                .columns(List.of(details.ownerFkColumn))
                .referencedTable(details.ownerEntity.getTableName())
                .referencedColumns(List.of(details.ownerPkColumnName))
                .constraintName("fk_" + details.ownerFkColumn)
                .cascadeTypes(toCascadeList(manyToMany.cascade()))
                .orphanRemoval(false)
                .fetchType(safeFetch(manyToMany.fetch(), FetchType.LAZY))
                .build();
        joinTableEntity.getRelationships().add(ownerRelationship);

        RelationshipModel inverseRelationship = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_MANY)
                .columns(List.of(details.inverseFkColumn))
                .referencedTable(details.referencedEntity.getTableName())
                .referencedColumns(List.of(details.inversePkColumnName))
                .constraintName("fk_" + details.inverseFkColumn)
                .cascadeTypes(toCascadeList(manyToMany.cascade()))
                .orphanRemoval(false)
                .fetchType(safeFetch(manyToMany.fetch(), FetchType.LAZY))
                .build();
        joinTableEntity.getRelationships().add(inverseRelationship);
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            Element element = declaredType.asElement();
            if (element instanceof TypeElement) return (TypeElement) element;
        }
        return null;
    }

    private record JoinTableDetails(
            String joinTableName,
            String ownerFkColumn,
            String inverseFkColumn,
            String ownerPkColumnName,
            String inversePkColumnName,
            EntityModel ownerEntity,
            EntityModel referencedEntity
    ) {}
}