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
    
    /**
     * Validate that the provided ExecutableElement represents a valid JavaBean-style property getter.
     *
     * <p>Performs the following checks and throws IllegalArgumentException if any fail:
     * <ul>
     *   <li>Method must have no parameters.</li>
     *   <li>Return type must not be void.</li>
     *   <li>Method name "getClass" is rejected.</li>
     *   <li>Methods starting with "is" (and longer than "is") must return `boolean` or `java.lang.Boolean`.</li>
     *   <li>For "getXxx" (length &gt; 3) and "isXxx" (length &gt; 2), the character following the prefix must be uppercase
     *       (enforces typical JavaBeans capitalization).</li>
     * </ul>
     *
     * @param getter the method element to validate as a property getter
     * @throws IllegalArgumentException if the element does not satisfy getter constraints listed above
     */
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

    /**
     * Returns the property name represented by the getter.
     *
     * Derives the name using JavaBeans conventions (e.g. `getFoo()` or `isFoo()` → `foo`), by extracting
     * and decapitalizing the getter's simple name.
     *
     * @return the derived property name
     */
    @Override
    public String name() {
        return extractPropertyName(getter.getSimpleName().toString());
    }
    
    /**
     * Derives the JavaBean property name from a getter method name.
     *
     * <p>Converts "getXxx" to "xxx" and "isXxx" to "xxx" using
     * {@link java.beans.Introspector#decapitalize(String)} to apply standard
     * JavaBeans decapitalization rules. If the name doesn't match those
     * prefixes the method decapitalizes and returns the original name.</p>
     *
     * @param methodName the simple name of a getter method (e.g. "getName" or "isActive")
     * @return the corresponding property name (e.g. "name" or "active")
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

    /**
     * Returns the declared return type of the underlying property getter.
     *
     * @return the getter method's {@link TypeMirror} representing the property's type
     */
    @Override
    public TypeMirror type() {
        return getter.getReturnType();
    }

    /**
     * Returns true if the getter's return type is a java.util.Collection (after erasure).
     *
     * This checks that the getter's return type is a declared type and that its erasure is
     * assignable to the erasure of java.util.Collection. If the return type is not a
     * declared type or the Collection type element cannot be resolved, this method returns false.
     *
     * @return true when the getter's return type is a Collection (ignoring generics); false otherwise
     */
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

    /**
     * Resolves the declared type of the getter's nth generic type argument, if available.
     *
     * <p>For a getter whose return type is a declared (parameterized) type, attempts to
     * return the type argument at the given index as a DeclaredType. Handles these cases:
     * <ul>
     *   <li>DeclaredType: returned directly when present at the index.</li>
     *   <li>WildcardType: returns the extends bound if present, otherwise the super bound, when that bound is a DeclaredType.</li>
     *   <li>TypeVariable: returns the upper bound when it is a DeclaredType.</li>
     * </ul>
     * If the return type is not a declared type, the index is out of range, or the resolved
     * argument cannot be represented as a DeclaredType, an empty Optional is returned.
     *
     * @param idx zero-based index of the generic type argument to resolve
     * @return an Optional containing the DeclaredType of the requested generic argument, or empty if not resolvable
     */
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

    /**
     * Returns the specified annotation instance present on the underlying getter method.
     *
     * @param <A> the annotation type
     * @param ann the annotation class to look up on the getter
     * @return the annotation instance if present on the getter, or {@code null} if not found
     */
    @Override
    public <A extends Annotation> A getAnnotation(Class<A> ann) {
        return getter.getAnnotation(ann);
    }

    /**
     * Returns true if the underlying getter method is annotated with the specified annotation.
     *
     * @param ann the annotation class to look for on the getter
     * @return true if the getter has the given annotation, false otherwise
     */
    @Override
    public boolean hasAnnotation(Class<? extends Annotation> ann) {
        return getter.getAnnotation(ann) != null;
    }

    /**
     * Returns the underlying element (the getter method) to be used in compiler diagnostics.
     *
     * @return the ExecutableElement that represents the property getter
     */
    @Override
    public Element elementForDiagnostics() {
        return getter;
    }

    /**
     * Returns the access kind for this descriptor.
     *
     * @return {@link AccessKind#PROPERTY} indicating the descriptor represents property access via a getter.
     */
    @Override
    public AccessKind accessKind() {
        return AccessKind.PROPERTY;
    }
}
