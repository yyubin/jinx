package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
                        entityModel.setValid(false);
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
            if (enclosed.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) enclosed;
            if (field.getAnnotation(Id.class) == null && field.getAnnotation(EmbeddedId.class) == null) {
                continue;
            }

            GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
            if (gv != null && (gv.strategy() == GenerationType.IDENTITY || gv.strategy() == GenerationType.AUTO)) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        String.format("IDENTITY generation strategy in TABLE_PER_CLASS inheritance may cause duplicate IDs. " +
                                        "Consider using SEQUENCE or TABLE strategy in entity %s, field %s",
                                entityModel.getEntityName(), field.getSimpleName()),
                        field);
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
                        processSingleJoinedChild(childEntity, parentEntity, childType);
                        checkIdentityStrategy(childType, childEntity);
                    }
                });
    }

    public record JoinPair(ColumnModel parent, String childName) {}

    private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, TypeElement childType) {
        List<ColumnModel> parentPkCols = context.findAllPrimaryKeyColumns(parentEntity);
        if (parentPkCols.isEmpty()) {
            childEntity.setValid(false);
            return;
        }

        List<JoinPair> joinPairs = resolvePrimaryKeyJoinPairs(childType, parentPkCols);

        for (JoinPair jp : joinPairs) {
            ColumnModel parentPk = jp.parent;
            String childColName = jp.childName;
            ColumnModel existing = childEntity.getColumns().get(childColName);
            if (existing != null) {
                if (!existing.getJavaType().equals(parentPk.getJavaType())
                        || !existing.isPrimaryKey()
                        || existing.isNullable()) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "JOINED inheritance column mismatch for '" + childColName + "' in " + childEntity.getEntityName());
                    childEntity.setValid(false);
                    return;
                }
            } else {
                ColumnModel idAsFk = ColumnModel.builder()
                        .columnName(childColName)
                        .javaType(parentPk.getJavaType())
                        .isPrimaryKey(true)
                        .isNullable(false)
                        .build();
                childEntity.getColumns().put(childColName, idAsFk);
            }
        }

        RelationshipModel relationship = RelationshipModel.builder()
                .type(RelationshipType.JOINED_INHERITANCE)
                .columns(joinPairs.stream().map(j -> j.childName).toList())
                .referencedTable(parentEntity.getTableName())
                .referencedColumns(joinPairs.stream().map(j -> j.parent.getColumnName()).toList())
                .constraintName(context.getNaming().fkName(
                        childEntity.getTableName(),
                        joinPairs.stream().map(j -> j.childName).toList(),
                        parentEntity.getTableName(),
                        joinPairs.stream().map(j -> j.parent.getColumnName()).toList()))
                .build();

        childEntity.getRelationships().put(relationship.getConstraintName(), relationship);
        childEntity.setParentEntity(parentEntity.getEntityName());
        childEntity.setInheritance(InheritanceType.JOINED);
    }

    private List<JoinPair> resolvePrimaryKeyJoinPairs(TypeElement childType, List<ColumnModel> parentPkCols) {
        List<PrimaryKeyJoinColumn> annotations = collectPrimaryKeyJoinColumns(childType);
        if (annotations.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    String.format("Jinx is creating a default foreign key for the JOINED inheritance of entity '%s'. " +
                        "To disable this constraint, you must explicitly use @PrimaryKeyJoinColumn along with @JoinColumn and @ForeignKey(ConstraintMode.NO_CONSTRAINT).",
                        childType.getQualifiedName()),
                    childType);
            return parentPkCols.stream()
                    .map(col -> new JoinPair(col, col.getColumnName()))
                    .toList();
        }

        // 개수 검증
        if (annotations.size() != parentPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    String.format("JOINED inheritance PK mapping mismatch in %s: expected %d columns, but got %d",
                            childType.getQualifiedName(), parentPkCols.size(), annotations.size()));
            throw new IllegalStateException("PK mapping size mismatch");
        }

        List<JoinPair> result = new ArrayList<>();
        try {
            for (int i = 0; i < annotations.size(); i++) {
                PrimaryKeyJoinColumn anno = annotations.get(i);
                ColumnModel parentRef = resolveParentReference(parentPkCols, anno, i);
                String childName = anno.name().isEmpty() ? parentRef.getColumnName() : anno.name();
                result.add(new JoinPair(parentRef, childName));
            }
        } catch (IllegalStateException ex) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Invalid @PrimaryKeyJoinColumn on " + childType.getQualifiedName() + ": " + ex.getMessage(), childType);
            throw ex; // Re-throw to maintain existing behavior for now
        }
        return result;
    }

    private List<PrimaryKeyJoinColumn> collectPrimaryKeyJoinColumns(TypeElement childType) {
        List<PrimaryKeyJoinColumn> result = new ArrayList<>();

        PrimaryKeyJoinColumns multi = childType.getAnnotation(PrimaryKeyJoinColumns.class);
        if (multi != null && multi.value().length > 0) {
            return Arrays.asList(multi.value());
        }

        PrimaryKeyJoinColumn single = childType.getAnnotation(PrimaryKeyJoinColumn.class);
        if (single != null) {
            result.add(single);
        }

        return result;
    }

    private ColumnModel resolveParentReference(List<ColumnModel> parentPkCols, PrimaryKeyJoinColumn anno, int index) {
        if (!anno.referencedColumnName().trim().isEmpty()) {
            return parentPkCols.stream()
                    .filter(col -> col.getColumnName().equals(anno.referencedColumnName()))
                    .findFirst()
                    .orElseThrow(() -> {
                        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "Referenced column '" + anno.referencedColumnName() + "' not found in parent primary keys");
                        return new IllegalStateException("Invalid referencedColumnName: " + anno.referencedColumnName());
                    });
        }
        return parentPkCols.get(index);
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

        parentEntity.getConstraints().values().forEach(constraint -> {
            ConstraintModel copiedConstraint = ConstraintModel.builder()
                    .name(constraint.getName())
                    .type(constraint.getType())
                    .columns(new ArrayList<>(constraint.getColumns()))
                    .referencedTable(constraint.getReferencedTable())
                    .referencedColumns(new ArrayList<>(constraint.getReferencedColumns()))
                    .build();
            childEntity.getConstraints().put(copiedConstraint.getName(), copiedConstraint);
        });
    }
}