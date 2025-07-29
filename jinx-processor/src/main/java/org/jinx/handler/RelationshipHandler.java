package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

        if (manyToOne != null || oneToOne != null) {
            processToOneRelationship(field, ownerEntity);
        } else if (manyToMany != null) {
            if (!manyToMany.mappedBy().isEmpty()) {
                return;
            }
            processManyToMany(field, ownerEntity);
        }
    }

    private void processToOneRelationship(VariableElement field, EntityModel ownerEntity) {
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn == null) return;

        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
        if (referencedTypeElement == null) return;
        EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;
        Optional<String> referencedPkColumnName = context.findPrimaryKeyColumnName(referencedEntity);
        if (referencedPkColumnName.isEmpty()) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have a primary key (@Id).", field);
            referencedEntity.setValid(false);
            return;
        }

        ColumnModel referencedPkColumn = referencedEntity.getColumns().get(referencedPkColumnName.get());
        if (referencedPkColumn == null) return;

        String pkName = referencedPkColumnName.get();
        String fkColumnName = joinColumn.name().isEmpty() ? field.getSimpleName().toString() + "_" + pkName : joinColumn.name();
        MapsId mapsId = field.getAnnotation(MapsId.class);

        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(referencedPkColumn.getJavaType())
                .isPrimaryKey(mapsId != null)
                .isNullable(mapsId == null)
                .isUnique(field.getAnnotation(OneToOne.class) != null && mapsId == null)
                .generationStrategy(GenerationStrategy.NONE)
                .build();
        if (ownerEntity.getColumns().containsKey(fkColumnName)) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate column name '" + fkColumnName + "' in entity " + ownerEntity.getEntityName(), field);
            return;
        }
        ownerEntity.getColumns().putIfAbsent(fkColumnName, fkColumn);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(field.getAnnotation(ManyToOne.class) != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .column(fkColumnName)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumn(referencedPkColumnName.get())
                .mapsId(mapsId != null)
                .constraintName(joinColumn.name())
                .build();
        ownerEntity.getRelationships().add(relationship);
    }

    private void processManyToMany(VariableElement field, EntityModel ownerEntity) {
        JoinTableDetails details = getJoinTableDetails(field, ownerEntity);
        if (details == null) return;

        if (context.getSchemaModel().getEntities().containsKey(details.joinTableName)) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate join table entity '" + details.joinTableName + "'", field);
            return;
        }

        EntityModel joinTableEntity = createJoinTableEntity(details);
        addRelationshipsToJoinTable(joinTableEntity, details);

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private JoinTableDetails getJoinTableDetails(VariableElement field, EntityModel ownerEntity) {
        JoinTable joinTable = field.getAnnotation(JoinTable.class);
        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
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

        // 조인 테이블 이름 결정
        String joinTableName;
        if (joinTable != null && !joinTable.name().isEmpty()) {
            joinTableName = joinTable.name();
        } else {
            List<String> tableNames = Arrays.asList(ownerTable.toLowerCase(), referencedTable.toLowerCase());
            Collections.sort(tableNames); // 알파벳순 정렬
            joinTableName = String.join("_", tableNames); // 정렬된 이름으로 조합
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
                .isJoinTable(true)
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

    private void addRelationshipsToJoinTable(EntityModel joinTableEntity, JoinTableDetails details) {
        RelationshipModel ownerRelationship = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_MANY)
                .column(details.ownerFkColumn)
                .referencedTable(details.ownerEntity.getTableName())
                .referencedColumn(details.ownerPkColumnName)
                .constraintName(details.ownerFkColumn)
                .build();
        joinTableEntity.getRelationships().add(ownerRelationship);

        RelationshipModel inverseRelationship = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_MANY)
                .column(details.inverseFkColumn)
                .referencedTable(details.referencedEntity.getTableName())
                .referencedColumn(details.inversePkColumnName)
                .constraintName(details.inverseFkColumn)
                .build();
        joinTableEntity.getRelationships().add(inverseRelationship);
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType) {
            Element element = ((DeclaredType) typeMirror).asElement();
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