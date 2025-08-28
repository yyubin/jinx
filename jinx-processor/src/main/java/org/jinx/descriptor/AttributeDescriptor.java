package org.jinx.descriptor;

import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.Optional;

public interface AttributeDescriptor {
    /**
 * Returns the attribute's name.
 *
 * <p>Example: for a field or property representing a collection of orders this might be {@code "orders"}.</p>
 *
 * @return the simple name of the attribute
 */
String name();                          /**
 * Returns the full declared type of the attribute.
 *
 * The returned TypeMirror represents the attribute's compile-time type, including any generic type information when present.
 *
 * @return the attribute's TypeMirror
 */
    TypeMirror type();                      /**
 * Returns whether this attribute represents a collection type.
 *
 * Use this to determine if genericArg(int) should be consulted for element type information.
 *
 * @return true if the attribute is a collection type, false otherwise
 */
    boolean isCollection();
    /**
 * Returns the declared generic type argument at the given index, if present.
 *
 * <p>For attributes with parameterized types (for example collections or other generic types),
 * this returns the generic argument as a {@link DeclaredType}. If the attribute is not
 * parameterized or the requested index is out of range, an empty {@link Optional} is returned.
 *
 * @param idx zero-based index of the generic type argument to retrieve
 * @return an {@link Optional} containing the declared generic argument at {@code idx}, or
 *         {@link Optional#empty()} if not available
 */
Optional<DeclaredType> genericArg(int idx);

    /**
 * Returns the annotation instance of the specified annotation type if present on the attribute.
 *
 * @param <A> the annotation type
 * @param ann the annotation class to retrieve
 * @return the annotation instance if present, or {@code null} if the attribute is not annotated with the given type
 */
<A extends Annotation> A getAnnotation(Class<A> ann);
    /**
 * Returns true if this attribute is annotated with the specified annotation type.
 *
 * @param ann the annotation class to look for
 * @return true if an annotation of the given type is present on the attribute, false otherwise
 */
boolean hasAnnotation(Class<? extends Annotation> ann);

    /**
 * Returns the source element to use when reporting diagnostics for this attribute.
 *
 * <p>The returned {@link Element} identifies the location in source (for example, a field or
 * accessor) where compiler errors or warnings related to this attribute should be reported.
 *
 * @return the element to attach diagnostics to (non-null)
 */
Element elementForDiagnostics();        /**
 * Returns how the attribute is accessed (either as a FIELD or as a PROPERTY).
 *
 * <p>Used to determine whether code should read/write the attribute directly via a field
 * or via accessor methods (getter/setter).</p>
 *
 * @return the AccessKind for this attribute
 */
    AccessKind accessKind();                // FIELD or PROPERTY
    enum AccessKind { FIELD, PROPERTY }
}
