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

    @Override
    public String name() {
        return field.getSimpleName().toString();
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
        if (!(type instanceof DeclaredType declaredType)) {
            return Optional.empty();
        }
        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.size() <= idx || !(typeArgs.get(idx) instanceof DeclaredType argType)) {
            return Optional.empty();
        }
        return Optional.of(argType);
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