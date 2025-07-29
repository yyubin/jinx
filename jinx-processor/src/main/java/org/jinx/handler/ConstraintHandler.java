package org.jinx.handler;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import org.jinx.annotation.Constraint;
import org.jinx.annotation.Constraints;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConstraintHandler {
    private final ProcessingContext context;

    public ConstraintHandler(ProcessingContext context) {
        this.context = context;
    }

    public void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints) {
        Constraint constraint = element.getAnnotation(Constraint.class);
        Constraints constraintsAnno = element.getAnnotation(Constraints.class);

        List<Constraint> constraintList = new ArrayList<>();
        if (constraint != null) constraintList.add(constraint);
        if (constraintsAnno != null) constraintList.addAll(Arrays.asList(constraintsAnno.value()));

        if (constraintList.isEmpty()) return;

        for (Constraint c : constraintList) {
            ConstraintType type = c.type();
            if (type == ConstraintType.AUTO) {
                type = inferConstraintType(element, fieldName);
                if (type == null) continue;
            }

            String checkExpr = type == ConstraintType.CHECK ? c.expression() : null;
            if (type == ConstraintType.CHECK && checkExpr.isBlank()) continue;

            ConstraintModel constraintModel = ConstraintModel.builder()
                    .name(c.value())
                    .type(type)
                    .column(fieldName)
                    .checkClause(checkExpr)
                    .build();

            if (type == ConstraintType.FOREIGN_KEY && element.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) element;
                TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
                if (referencedTypeElement != null) {
                    EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
                    if (referencedEntity != null) {
                        String referencedPkColumnName = findPrimaryKeyColumnName(referencedEntity);
                        constraintModel.setReferencedTable(referencedEntity.getTableName());
                        constraintModel.setReferencedColumn(referencedPkColumnName);
                        constraintModel.setName(c.value().isEmpty() ? "fk_" + fieldName : c.value());
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            if (c.onDelete() != OnDeleteAction.NO_ACTION) constraintModel.setOnDelete(c.onDelete());
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) constraintModel.setOnUpdate(c.onUpdate());

            constraints.add(constraintModel);
        }
    }

    private ConstraintType inferConstraintType(Element element, String fieldName) {
        if (element.getKind() == ElementKind.FIELD) {
            VariableElement field = (VariableElement) element;
            if (field.getAnnotation(ManyToOne.class) != null || field.getAnnotation(OneToOne.class) != null || field.getAnnotation(JoinColumn.class) != null) {
                return ConstraintType.FOREIGN_KEY;
            }
            if (field.getAnnotation(Column.class) != null && field.getAnnotation(Column.class).unique()) {
                return ConstraintType.UNIQUE;
            }
        }
        return null;
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType) {
            Element element = ((DeclaredType) typeMirror).asElement();
            if (element instanceof TypeElement) return (TypeElement) element;
        }
        return null;
    }

    private String findPrimaryKeyColumnName(EntityModel entityModel) {
        return entityModel.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .findFirst()
                .orElse("id");
    }
}
