package org.jinx.handler;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Optional;

public class InheritanceHandler {
    private final ProcessingContext context;

    public InheritanceHandler(ProcessingContext context) {
        this.context = context;
    }

    public void resolveInheritance(TypeElement typeElement, EntityModel entityModel) {
        Inheritance inheritance = typeElement.getAnnotation(Inheritance.class);
        if (inheritance == null) return;

        if (inheritance.strategy() == InheritanceType.SINGLE_TABLE) {
            entityModel.setInheritance(org.jinx.model.InheritanceType.SINGLE_TABLE);
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
        } else if (inheritance.strategy() == InheritanceType.JOINED) {
            entityModel.setInheritance(org.jinx.model.InheritanceType.JOINED);
            findAndProcessJoinedChildren(entityModel, typeElement);
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

        // 전체 엔티티 목록에서 자식 엔티티를 탐색
        context.getSchemaModel().getEntities().values().stream()
                .filter(childCandidate -> !childCandidate.getEntityName().equals(parentEntity.getEntityName())) // 자기 자신 제외
                .forEach(childEntity -> {
                    TypeElement childType = context.getElementUtils().getTypeElement(childEntity.getEntityName());
                    if (childType != null && context.getTypeUtils().isSubtype(childType.asType(), parentType.asType())) {
                        processSingleJoinedChild(childEntity, parentEntity, parentPkColumnName, parentPkColumn);
                    }
                });
    }

    private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, String parentPkColumnName, ColumnModel parentPkColumn) {
        childEntity.setParentEntity(parentEntity.getEntityName());
        childEntity.setInheritance(org.jinx.model.InheritanceType.JOINED);

        ColumnModel idColumnAsFk = ColumnModel.builder()
                .columnName(parentPkColumnName)
                .javaType(parentPkColumn.getJavaType())
                .isPrimaryKey(true)
                .isNullable(false)
                .build();
        if (childEntity.getColumns().containsKey(idColumnAsFk.getColumnName())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Duplicate column name '" + idColumnAsFk.getColumnName() + "' in child entity " + childEntity.getEntityName());
            childEntity.setValid(false); // 중복 오류 시 유효성 플래그 설정
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
}