package org.jinx.descriptor;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

public record PropertyAttributeDescriptor(
        ExecutableElement getter,
        Types typeUtils,
        Elements elements
) implements AttributeDescriptor {

    public PropertyAttributeDescriptor {
        validateGetter(getter);
    }
    
    private static void validateGetter(ExecutableElement getter) {
        // Must have no parameters
        if (getter.getParameters() != null && !getter.getParameters().isEmpty()) {
            throw new IllegalArgumentException("Getter must have no parameters: " + getter);
        }
        
        // Must have non-void return type
        if (getter.getReturnType().getKind() == TypeKind.VOID) {
            throw new IllegalArgumentException("Getter must have a non-void return type: " + getter);
        }
        
        String methodName = getter.getSimpleName().toString();
        
        // Exclude getClass() - it's Object method, not a property getter
        if ("getClass".equals(methodName)) {
            throw new IllegalArgumentException("getClass() is not a valid property getter: " + getter);
        }
        
        // Validate isXxx methods must return boolean/Boolean
        if (methodName.startsWith("is") && methodName.length() > 2) {
            String returnTypeName = getter.getReturnType().toString();
            if (!"boolean".equals(returnTypeName) && !"java.lang.Boolean".equals(returnTypeName)) {
                throw new IllegalArgumentException("isXxx getter must return boolean or Boolean, but returns " + 
                    returnTypeName + ": " + getter);
            }
        }
        
        // Validate proper capitalization after prefix
        if (methodName.startsWith("get") && methodName.length() > 3) {
            char firstChar = methodName.charAt(3);
            if (!Character.isUpperCase(firstChar)) {
                throw new IllegalArgumentException("getXxx method must have uppercase character after 'get': " + getter);
            }
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            char firstChar = methodName.charAt(2);
            if (!Character.isUpperCase(firstChar)) {
                throw new IllegalArgumentException("isXxx method must have uppercase character after 'is': " + getter);
            }
        }
    }

    @Override
    public String name() {
        return extractPropertyName(getter.getSimpleName().toString());
    }
    
    /**
     * Extract property name using consistent JavaBeans rules
     */
    private static String extractPropertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            // getXxx -> xxx (with proper decapitalization)
            return Introspector.decapitalize(methodName.substring(3));
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            // isXxx -> xxx (with proper decapitalization)
            return Introspector.decapitalize(methodName.substring(2));
        }
        // Fallback - shouldn't reach here due to validation
        return Introspector.decapitalize(methodName);
    }

    @Override
    public TypeMirror type() {
        return getter.getReturnType();
    }

    @Override
    public boolean isCollection() {
        TypeMirror t = getter.getReturnType();
        if (!(t instanceof DeclaredType dt)) return false;

        TypeElement collTe = elements.getTypeElement("java.util.Collection");
        if (collTe == null) return false;

        TypeMirror lhs = typeUtils.erasure(dt);
        TypeMirror rhs = typeUtils.erasure(collTe.asType());
        return typeUtils.isAssignable(lhs, rhs);
    }

    @Override
    public Optional<DeclaredType> genericArg(int idx) {
        TypeMirror t = getter.getReturnType();
        if (!(t instanceof DeclaredType dt)) return Optional.empty();

        List<? extends TypeMirror> args = dt.getTypeArguments();
        if (args == null || args.size() <= idx) return Optional.empty();

        TypeMirror arg = args.get(idx);

        // 와일드카드/타입변수까지
        if (arg instanceof WildcardType wt) {
            TypeMirror bound = wt.getExtendsBound() != null ? wt.getExtendsBound() : wt.getSuperBound();
            if (bound instanceof DeclaredType bdt) return Optional.of(bdt);
            return Optional.empty();
        }
        if (arg instanceof TypeVariable tv) {
            TypeMirror upper = tv.getUpperBound();
            if (upper instanceof DeclaredType bdt) return Optional.of(bdt);
            return Optional.empty();
        }

        return (arg instanceof DeclaredType adt) ? Optional.of(adt) : Optional.empty();
    }

    @Override
    public <A extends Annotation> A getAnnotation(Class<A> ann) {
        return getter.getAnnotation(ann);
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> ann) {
        return getter.getAnnotation(ann) != null;
    }

    @Override
    public Element elementForDiagnostics() {
        return getter;
    }

    @Override
    public AccessKind accessKind() {
        return AccessKind.PROPERTY;
    }
}
