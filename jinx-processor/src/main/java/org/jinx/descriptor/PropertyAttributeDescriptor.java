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
        if (!isValidGetter(getter)) {
            throw new IllegalArgumentException("Invalid getter: " + getter);
        }
    }
    
    public static boolean isValidGetter(ExecutableElement getter) {
        // Must have no parameters
        if (getter.getParameters() != null && !getter.getParameters().isEmpty()) {
            return false;
        }
        
        // Must have non-void return type
        if (getter.getReturnType().getKind() == TypeKind.VOID) {
            return false;
        }
        
        String methodName = getter.getSimpleName().toString();
        
        // Exclude getClass() - it's Object method, not a property getter
        if ("getClass".equals(methodName)) {
            return false;
        }
        
        // Must start with getXxx or isXxx (getClass 제외)
        boolean isGet = methodName.startsWith("get") && methodName.length() > 3;
        boolean isIs  = methodName.startsWith("is")  && methodName.length() > 2;
        if (!isGet && !isIs) {
            return false;
        }
        
        // Validate isXxx methods must return boolean/Boolean
        if (methodName.startsWith("is") && methodName.length() > 2) {
            TypeKind rk = getter.getReturnType().getKind();
            String returnTypeName = getter.getReturnType().toString();
            if (!(rk == TypeKind.BOOLEAN || "java.lang.Boolean".equals(returnTypeName))) {
                return false;
            }
        }
        
        // Validate proper capitalization after prefix
        if (methodName.startsWith("get") && methodName.length() > 3) {
            char firstChar = methodName.charAt(3);
            if (!Character.isUpperCase(firstChar)) {
                return false;
            }
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            char firstChar = methodName.charAt(2);
            if (!Character.isUpperCase(firstChar)) {
                return false;
            }
        }
        
        return true;
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
