package org.jinx.descriptor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.Optional;

/**
 * An abstraction over a persistent attribute (field or property) of an entity.
 * <p>
 * This interface provides a unified way to access information about an attribute,
 * regardless of whether it is accessed via a field or a getter method.
 */
public interface AttributeDescriptor {
    /** @return The attribute name (e.g., "orders"). */
    String name();

    /** @return The full type of the attribute. */
    TypeMirror type();

    /** @return True if the attribute is a collection type. */
    boolean isCollection();

    /** @return The generic type argument at the specified index. */
    Optional<DeclaredType> genericArg(int idx);

    /**
     * Gets the annotation of the specified type on this attribute.
     * @param ann The annotation class.
     * @return The annotation instance, or null if not present.
     */
    <A extends Annotation> A getAnnotation(Class<A> ann);

    /**
     * Checks if the attribute has an annotation of the specified type.
     * @param ann The annotation class.
     * @return True if the annotation is present.
     */
    boolean hasAnnotation(Class<? extends Annotation> ann);

    /** @return The element to use for error reporting. */
    Element elementForDiagnostics();

    /** @return The kind of access: FIELD, PROPERTY, or RECORD_COMPONENT. */
    AccessKind accessKind();
    enum AccessKind { FIELD, PROPERTY, RECORD_COMPONENT }

    // Optional helpers for mirror-based lookup (no Class loading)
    default Optional<AnnotationMirror> findAnnotationMirror(String fqcn) {
        return elementForDiagnostics().getAnnotationMirrors().stream()
                .filter(am -> am.getAnnotationType().toString().equals(fqcn))
                .findFirst()
                .map(am -> (AnnotationMirror) am); // Cast Optional<? extends ...> to Optional<...>
    }

    default boolean hasAnnotation(String fqcn) {
        return findAnnotationMirror(fqcn).isPresent();
    }
}
