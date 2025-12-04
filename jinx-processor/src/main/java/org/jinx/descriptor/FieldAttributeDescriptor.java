package org.jinx.descriptor;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

public record FieldAttributeDescriptor(VariableElement field, Types typeUtils, Elements elements) implements AttributeDescriptor {

    @Override
    public String name() {
        String fieldName = field.getSimpleName().toString();

        // Apply JavaBeans normalization for boolean fields starting with "is"
        // to match the property name derived from their getter
        if (isBooleanField() && fieldName.startsWith("is") && fieldName.length() > 2) {
            char thirdChar = fieldName.charAt(2);
            // Only apply if third character is uppercase (e.g., "isPrimary", not "island")
            if (Character.isUpperCase(thirdChar)) {
                // Example: "isPrimary" -> "Primary" -> "primary"
                return java.beans.Introspector.decapitalize(fieldName.substring(2));
            }
        }

        return fieldName;
    }

    /**
     * Check if field type is boolean using TypeMirror comparison.
     * This is more reliable than toString() comparison in APT context.
     */
    private boolean isBooleanField() {
        TypeMirror fieldType = field.asType();

        // Check primitive boolean
        if (fieldType.getKind() == javax.lang.model.type.TypeKind.BOOLEAN) {
            return true;
        }

        // Check Boolean wrapper type using TypeMirror comparison
        TypeElement booleanWrapperType = elements.getTypeElement("java.lang.Boolean");
        if (booleanWrapperType != null) {
            return typeUtils.isSameType(fieldType, booleanWrapperType.asType());
        }

        return false;
    }

    @Override
    public TypeMirror type() {
        return field.asType();
    }

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

    @Override
    public Optional<DeclaredType> genericArg(int idx) {
        TypeMirror type = field.asType();
        if (!(type instanceof DeclaredType declaredType)) return Optional.empty();
        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.size() <= idx) return Optional.empty();

        TypeMirror arg = typeArgs.get(idx);
        if (arg instanceof WildcardType wt) {
            if (wt.getExtendsBound() != null) {
                TypeMirror bound = wt.getExtendsBound();
                return (bound instanceof DeclaredType bdt) ? Optional.of(bdt) : Optional.empty();
            }
            return Optional.empty();
        }
        if (arg instanceof TypeVariable tv) {
            TypeMirror upper = tv.getUpperBound();
            return (upper instanceof DeclaredType bdt) ? Optional.of(bdt) : Optional.empty();
        }
        return (arg instanceof DeclaredType adt) ? Optional.of(adt) : Optional.empty();
    }

    @Override
    public <A extends Annotation> A getAnnotation(Class<A> ann) {
        return field.getAnnotation(ann);
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> ann) {
        return field.getAnnotation(ann) != null;
    }

    @Override
    public Element elementForDiagnostics() {
        return field;
    }

    @Override
    public AccessKind accessKind() {
        return AccessKind.FIELD;
    }
}