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

public record RecordAttributeDescriptor(VariableElement recordComponent, Types typeUtils, Elements elements) implements AttributeDescriptor {

    @Override
    public String name() {
        return recordComponent.getSimpleName().toString();
    }

    @Override
    public TypeMirror type() {
        return recordComponent.asType();
    }

    @Override
    public boolean isCollection() {
        TypeMirror type = recordComponent.asType();
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
        TypeMirror type = recordComponent.asType();
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
        return recordComponent.getAnnotation(ann);
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> ann) {
        return recordComponent.getAnnotation(ann) != null;
    }

    @Override
    public Element elementForDiagnostics() {
        return recordComponent;
    }

    @Override
    public AccessKind accessKind() {
        return AccessKind.RECORD_COMPONENT;
    }
}