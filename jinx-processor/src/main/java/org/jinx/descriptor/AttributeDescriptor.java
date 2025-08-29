package org.jinx.descriptor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.Optional;

public interface AttributeDescriptor {
    String name();                          // 속성명 (orders)
    TypeMirror type();                      // 전체 타입
    boolean isCollection();
    Optional<DeclaredType> genericArg(int idx);

    <A extends Annotation> A getAnnotation(Class<A> ann);
    boolean hasAnnotation(Class<? extends Annotation> ann);

    Element elementForDiagnostics();        // 에러 표시 위치
    AccessKind accessKind();                // FIELD or PROPERTY
    enum AccessKind { FIELD, PROPERTY }

    // Optional helpers for mirror-based lookup (no Class loading)
    default Optional<AnnotationMirror> findAnnotationMirror(String fqcn) {
        return elementForDiagnostics().getAnnotationMirrors().stream()
                .filter(am -> am.getAnnotationType().toString().equals(fqcn))
                .findFirst();
    }

    default boolean hasAnnotation(String fqcn) {
        return findAnnotationMirror(fqcn).isPresent();
    }
}
