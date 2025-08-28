package org.jinx.descriptor;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

public record FieldAttributeDescriptor(VariableElement field, Types typeUtils, Elements elements) implements AttributeDescriptor {

    /**
     * Returns the simple name of the described field.
     *
     * @return the field's simple name as a String
     */
    @Override
    public String name() {
        return field.getSimpleName().toString();
    }

    /**
     * Returns the compile-time type of the underlying field.
     *
     * @return the field's TypeMirror (the declared type of the VariableElement)
     */
    @Override
    public TypeMirror type() {
        return field.asType();
    }

    /**
     * Returns true if the described field's declared type is a subtype of java.util.Collection.
     *
     * <p>Non-declared types (e.g., primitives, arrays) and cases where the Collection type element
     * cannot be resolved return false. The check compares erasures, so generic parameters are ignored.
     *
     * @return true when the field's type is assignable to java.util.Collection; false otherwise
     */
    @Override
    public boolean isCollection() {
        TypeMirror type = field.asType();
        if (!(type instanceof DeclaredType declaredType)) {
            return false;
        }
        TypeElement collTe = elements.getTypeElement("java.util.Collection");
        if (collTe == null) return false;

        TypeMirror lhs = typeUtils.erasure(declaredType);
        TypeMirror rhs = typeUtils.erasure(collTe.asType());
        return typeUtils.isAssignable(lhs, rhs);
    }

    /**
     * Returns the declared generic type argument at the given index for the field's declared type.
     *
     * If the field's type is not a declared (parameterized) type, the index is out of bounds, or the
     * type argument at the index is not itself a DeclaredType, this method returns Optional.empty().
     *
     * @param idx zero-based index of the generic type argument to retrieve
     * @return an Optional containing the DeclaredType of the requested generic argument, or Optional.empty()
     */
    @Override
    public Optional<DeclaredType> genericArg(int idx) {
        TypeMirror type = field.asType();
        if (!(type instanceof DeclaredType declaredType)) {
            return Optional.empty();
        }
        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.size() <= idx || !(typeArgs.get(idx) instanceof DeclaredType argType)) {
            return Optional.empty();
        }
        return Optional.of(argType);
    }

    /**
     * Returns the annotation of the specified type if present on the underlying field.
     *
     * @param <A> the annotation type
     * @param ann the annotation class to look up on the field
     * @return the annotation instance if present, or {@code null} if the field is not annotated with {@code ann}
     */
    @Override
    public <A extends Annotation> A getAnnotation(Class<A> ann) {
        return field.getAnnotation(ann);
    }

    /**
     * Returns true if the described field is annotated with the given annotation type.
     *
     * @param ann the annotation class to look for on the field
     * @return {@code true} if the annotation is present on the field, otherwise {@code false}
     */
    @Override
    public boolean hasAnnotation(Class<? extends Annotation> ann) {
        return field.getAnnotation(ann) != null;
    }

    /**
     * Returns the underlying element representing the field, suitable for use in compiler diagnostics.
     *
     * @return the VariableElement for this field (used for error/warning reporting)
     */
    @Override
    public Element elementForDiagnostics() {
        return field;
    }

    /**
     * Indicates that this descriptor represents a field-based attribute.
     *
     * @return {@link AccessKind#FIELD}
     */
    @Override
    public AccessKind accessKind() {
        return AccessKind.FIELD;
    }
}