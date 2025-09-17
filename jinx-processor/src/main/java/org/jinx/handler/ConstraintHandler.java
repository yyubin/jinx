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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConstraintHandler {
    private final ProcessingContext context;

    public ConstraintHandler(ProcessingContext context) {
        this.context = context;
    }

    public void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints, String tableName) {
        Constraint c1 = element.getAnnotation(Constraint.class);
        Constraints cs = element.getAnnotation(Constraints.class);

        List<Constraint> list = new ArrayList<>();
        if (c1 != null) list.add(c1);
        if (cs != null) list.addAll(Arrays.asList(cs.value()));
        if (list.isEmpty()) return;

        for (Constraint c : list) {
            ConstraintType type = c.type();
            if (type == ConstraintType.AUTO) {
                type = inferConstraintType(element, fieldName);
                if (type == null) continue;
            }
            String checkExpr = (type == ConstraintType.CHECK) ? nullIfBlank(c.expression()) : null;
            if (type == ConstraintType.CHECK && checkExpr == null) continue;

            ConstraintModel m = ConstraintModel.builder()
                    .tableName(tableName)
                    .name(nullIfBlank(c.value()))
                    .type(type)
                    .columns(fieldName != null ? List.of(fieldName) : List.of())
                    .checkClause(checkExpr) // <- nullable
                    .build();

            if (c.onDelete() != OnDeleteAction.NO_ACTION) m.setOnDelete(c.onDelete());
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) m.setOnUpdate(c.onUpdate());

            constraints.add(m);
        }
    }

    public void processConstraints(AttributeDescriptor attr, String fieldName, List<ConstraintModel> constraints, String tableName) {
        Constraint c1 = attr.getAnnotation(Constraint.class);
        Constraints cs = attr.getAnnotation(Constraints.class);

        List<Constraint> list = new ArrayList<>();
        if (c1 != null) list.add(c1);
        if (cs != null) list.addAll(Arrays.asList(cs.value()));
        if (list.isEmpty()) return;

        for (Constraint c : list) {
            ConstraintType type = c.type();
            if (type == ConstraintType.AUTO) {
                type = inferConstraintType(attr, fieldName);
                if (type == null) continue;
            }
            String checkExpr = (type == ConstraintType.CHECK) ? nullIfBlank(c.expression()) : null;
            if (type == ConstraintType.CHECK && checkExpr == null) continue;

            ConstraintModel m = ConstraintModel.builder()
                    .tableName(tableName)
                    .name(nullIfBlank(c.value()))
                    .type(type)
                    .columns(List.of(fieldName))
                    .checkClause(checkExpr) // <- nullable
                    .build();

            if (c.onDelete() != OnDeleteAction.NO_ACTION) m.setOnDelete(c.onDelete());
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) m.setOnUpdate(c.onUpdate());

            constraints.add(m);
        }
    }

    private ConstraintType inferConstraintType(Element element, String fieldName) {
        Column col = element.getAnnotation(Column.class);
        if (col != null && col.unique()) return ConstraintType.UNIQUE;
        return null;
    }

    private ConstraintType inferConstraintType(AttributeDescriptor attr, String fieldName) {
        Column col = attr.getAnnotation(Column.class);
        if (col != null && col.unique()) return ConstraintType.UNIQUE;
        return null;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
