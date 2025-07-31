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
                    .columns(List.of(fieldName))
                    .checkClause(checkExpr)
                    .build();
            // FIX: RelationshipModel과 ConstraintModel 양쪽 모두 외래 키(FK)를 생성할 수 있어, 중복이나 혼란의 여지가 있다
            // 예를 들어 MigrationVisitor가 두 Diff를 모두 방문하면 FK가 두 번 생성될 수 있음
            // ConstraintType에서 FOREINGN_KEY는 제외하고 전부 Relationship으로 해결하도록 주석 처리
//            if (type == ConstraintType.FOREIGN_KEY && element.getKind() == ElementKind.FIELD) {
//                VariableElement field = (VariableElement) element;
//                TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
//                if (referencedTypeElement != null) {
//                    EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());
//                    if (referencedEntity != null) {
//                        String referencedPkColumnName = findPrimaryKeyColumnName(referencedEntity);
//                        constraintModel.setReferencedTable(referencedEntity.getTableName());
//                        constraintModel.setReferencedColumns(List.of(referencedPkColumnName));
//                        constraintModel.setName(c.value().isEmpty() ? "fk_" + fieldName : c.value());
//                    } else {
//                        continue;
//                    }
//                } else {
//                    continue;
//                }
//            }

            if (c.onDelete() != OnDeleteAction.NO_ACTION) constraintModel.setOnDelete(c.onDelete());
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) constraintModel.setOnUpdate(c.onUpdate());

            constraints.add(constraintModel);
        }
    }

    private ConstraintType inferConstraintType(Element element, String fieldName) {
        if (element.getKind() == ElementKind.FIELD) {
            VariableElement field = (VariableElement) element;
            if (field.getAnnotation(Column.class) != null && field.getAnnotation(Column.class).unique()) {
                return ConstraintType.UNIQUE;
            }
        }
        return null;
    }

}
