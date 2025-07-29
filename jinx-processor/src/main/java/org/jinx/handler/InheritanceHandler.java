package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Optional;

public class InheritanceHandler {
    private final ProcessingContext context;

    public InheritanceHandler(ProcessingContext context) {
        this.context = context;
    }

    public void resolveInheritance(TypeElement typeElement, EntityModel entityModel) {
        Inheritance inheritance = typeElement.getAnnotation(Inheritance.class);
        if (inheritance == null) return;

        switch (inheritance.strategy()) {
            case SINGLE_TABLE:
                entityModel.setInheritance(InheritanceType.SINGLE_TABLE);
                DiscriminatorColumn discriminatorColumn = typeElement.getAnnotation(DiscriminatorColumn.class);
                if (discriminatorColumn != null) {
                    ColumnModel dColumn = ColumnModel.builder()
                            .columnName(discriminatorColumn.name().isEmpty() ? "dtype" : discriminatorColumn.name())
                            .javaType("java.lang.String")
                            .isPrimaryKey(false)
                            .isNullable(false)
                            .generationStrategy(GenerationStrategy.NONE)
                            .build();
                    if (entityModel.getColumns().containsKey(dColumn.getColumnName())) {
                        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "Duplicate column name '" + dColumn.getColumnName() + "' for discriminator in entity " + entityModel.getEntityName(), typeElement);
                        return;
                    }
                    entityModel.getColumns().putIfAbsent(dColumn.getColumnName(), dColumn);
                }
                DiscriminatorValue discriminatorValue = typeElement.getAnnotation(DiscriminatorValue.class);
                if (discriminatorValue != null) {
                    entityModel.setDiscriminatorValue(discriminatorValue.value());
                }
                break;
            case JOINED:
                entityModel.setInheritance(InheritanceType.JOINED);
                findAndProcessJoinedChildren(entityModel, typeElement);
                break;
            case TABLE_PER_CLASS:
                entityModel.setInheritance(InheritanceType.TABLE_PER_CLASS);
                findAndProcessTablePerClassChildren(entityModel, typeElement);
                checkIdentityStrategy(typeElement, entityModel); // Check for IDENTITY strategy
                break;
        }
    }

    private void checkIdentityStrategy(TypeElement typeElement, EntityModel entityModel) {
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosed;
                if (field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null) {
                    GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
                    if (gv != null && (gv.strategy() == GenerationType.IDENTITY || gv.strategy() == GenerationType.AUTO)) {
                        context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                "Using IDENTITY generation strategy in TABLE_PER_CLASS inheritance is not supported by JPA specification and may cause duplicate IDs across tables.",
                                field);
                    }
                }
            }
        }
    }

    private void findAndProcessJoinedChildren(EntityModel parentEntity, TypeElement parentType) {
        Optional<String> optParentPkColumnName = context.findPrimaryKeyColumnName(parentEntity);
        if (optParentPkColumnName.isEmpty()) {
            parentEntity.setValid(false);
            return;
        }
        String parentPkColumnName = optParentPkColumnName.get();
        ColumnModel parentPkColumn = parentEntity.getColumns().get(parentPkColumnName);
        if (parentPkColumn == null) return;

        context.getSchemaModel().getEntities().values().stream()
                .filter(childCandidate -> !childCandidate.getEntityName().equals(parentEntity.getEntityName()))
                .forEach(childEntity -> {
                    TypeElement childType = context.getElementUtils().getTypeElement(childEntity.getEntityName());
                    if (childType != null && context.getTypeUtils().isSubtype(childType.asType(), parentType.asType())) {
                        processSingleJoinedChild(childEntity, parentEntity, parentPkColumnName, parentPkColumn);
                        checkIdentityStrategy(childType, childEntity); // Check children too
                    }
                });
    }

    private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, String parentPkColumnName, ColumnModel parentPkColumn) {
        childEntity.setParentEntity(parentEntity.getEntityName());
        childEntity.setInheritance(InheritanceType.JOINED);

        ColumnModel idColumnAsFk = ColumnModel.builder()
                .columnName(parentPkColumnName)
                .javaType(parentPkColumn.getJavaType())
                .isPrimaryKey(true)
                .isNullable(false)
                .build();
        if (childEntity.getColumns().containsKey(idColumnAsFk.getColumnName())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Duplicate column name '" + idColumnAsFk.getColumnName() + "' in child entity " + childEntity.getEntityName());
            childEntity.setValid(false);
            return;
        }
        childEntity.getColumns().putIfAbsent(idColumnAsFk.getColumnName(), idColumnAsFk);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(RelationshipType.JOINED_INHERITANCE)
                .column(parentPkColumnName)
                .referencedTable(parentEntity.getTableName())
                .referencedColumn(parentPkColumnName)
                .build();
        childEntity.getRelationships().add(relationship);
    }

    private void findAndProcessTablePerClassChildren(EntityModel parentEntity, TypeElement parentType) {
        context.getSchemaModel().getEntities().values().stream()
                .filter(childCandidate -> !childCandidate.getEntityName().equals(parentEntity.getEntityName()))
                .forEach(childEntity -> {
                    TypeElement childType = context.getElementUtils().getTypeElement(childEntity.getEntityName());
                    if (childType != null && context.getTypeUtils().isSubtype(childType.asType(), parentType.asType())) {
                        processSingleTablePerClassChild(childEntity, parentEntity);
                        checkIdentityStrategy(childType, childEntity); // Check children too
                    }
                });
    }

    private void processSingleTablePerClassChild(EntityModel childEntity, EntityModel parentEntity) {
        childEntity.setParentEntity(parentEntity.getEntityName());
        childEntity.setInheritance(InheritanceType.TABLE_PER_CLASS);

        parentEntity.getColumns().forEach((name, column) -> {
            if (!childEntity.getColumns().containsKey(name)) {
                ColumnModel copiedColumn = ColumnModel.builder()
                        .columnName(column.getColumnName())
                        .javaType(column.getJavaType())
                        .isPrimaryKey(column.isPrimaryKey())
                        .isNullable(column.isNullable())
                        .isUnique(column.isUnique())
                        .length(column.getLength())
                        .precision(column.getPrecision())
                        .scale(column.getScale())
                        .defaultValue(column.getDefaultValue())
                        .generationStrategy(column.getGenerationStrategy())
                        .sequenceName(column.getSequenceName())
                        .tableGeneratorName(column.getTableGeneratorName())
                        .isLob(column.isLob())
                        .jdbcType(column.getJdbcType())
                        .fetchType(column.getFetchType())
                        .isOptional(column.isOptional())
                        .isVersion(column.isVersion())
                        .conversionClass(column.getConversionClass())
                        .temporalType(column.getTemporalType())
                        .build();
                childEntity.getColumns().put(name, copiedColumn);
            }
        });

        parentEntity.getConstraints().forEach(constraint -> {
            ConstraintModel copiedConstraint = ConstraintModel.builder()
                    .name(constraint.getName())
                    .type(constraint.getType())
                    .columns(new ArrayList<>(constraint.getColumns()))
                    .referencedTable(constraint.getReferencedTable())
                    .referencedColumns(new ArrayList<>(constraint.getReferencedColumns()))
                    .build();
            childEntity.getConstraints().add(copiedConstraint);
        });
    }
}