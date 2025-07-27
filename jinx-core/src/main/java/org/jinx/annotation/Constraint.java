package org.jinx.annotation;

import org.jinx.model.ConstraintType;
import org.jinx.model.OnDeleteAction;
import org.jinx.model.OnUpdateAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Constraint {
    String value();                         // 제약조건 이름
    ConstraintType type();                  // 제약조건 종류
    OnDeleteAction onDelete() default OnDeleteAction.NO_ACTION;
    OnUpdateAction onUpdate() default OnUpdateAction.NO_ACTION;
}
