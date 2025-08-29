package org.jinx.handler;

import jakarta.persistence.Column;
import org.jinx.annotation.Constraint;
import org.jinx.annotation.Constraints;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.OnDeleteAction;
import org.jinx.model.OnUpdateAction;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class ConstraintHandler {
    private final ProcessingContext context;

    public ConstraintHandler(ProcessingContext context) {
        this.context = context;
    }

    public void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints, String tableName) {
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
                    .tableName(tableName)
                    .name(c.value())
                    .type(type)
                    .columns(fieldName != null ? List.of(fieldName) : List.of())
                    .checkClause(Optional.ofNullable(checkExpr))
                    .build();

            if (c.onDelete() != OnDeleteAction.NO_ACTION) constraintModel.setOnDelete(c.onDelete());
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) constraintModel.setOnUpdate(c.onUpdate());

            constraints.add(constraintModel);
        }
    }

    public void processConstraints(AttributeDescriptor attr, String fieldName, List<ConstraintModel> constraints, String tableName) {
        Constraint constraint = attr.getAnnotation(Constraint.class);
        Constraints constraintsAnno = attr.getAnnotation(Constraints.class);

        List<Constraint> constraintList = new ArrayList<>();
        if (constraint != null) constraintList.add(constraint);
        if (constraintsAnno != null) constraintList.addAll(Arrays.asList(constraintsAnno.value()));

        if (constraintList.isEmpty()) return;

        for (Constraint c : constraintList) {
            ConstraintType type = c.type();
            if (type == ConstraintType.AUTO) {
                type = inferConstraintType(attr, fieldName);
                if (type == null) continue;
            }

            String checkExpr = type == ConstraintType.CHECK ? c.expression() : null;
            if (type == ConstraintType.CHECK && checkExpr.isBlank()) continue;

            ConstraintModel constraintModel = ConstraintModel.builder()
                    .tableName(tableName)
                    .name(c.value())
                    .type(type)
                    .columns(List.of(fieldName))
                    .checkClause(Optional.ofNullable(checkExpr))
                    .build();

            if (c.onDelete() != OnDeleteAction.NO_ACTION) constraintModel.setOnDelete(c.onDelete());
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) constraintModel.setOnUpdate(c.onUpdate());

            constraints.add(constraintModel);
        }
    }

    private ConstraintType inferConstraintType(Element element, String fieldName) {
        Column col = element.getAnnotation(Column.class);
        if (col != null && col.unique()) {
            return ConstraintType.UNIQUE;
        }
        return null;
    }

    private ConstraintType inferConstraintType(AttributeDescriptor attr, String fieldName) {
        Column col = attr.getAnnotation(Column.class);
        if (col != null && col.unique()) {
            return ConstraintType.UNIQUE;
        }
        return null;
    }

}
