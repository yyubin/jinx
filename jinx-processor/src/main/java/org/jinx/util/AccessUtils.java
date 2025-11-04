package org.jinx.util;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;

import javax.lang.model.element.*;
import java.util.Set;

/**
 * Utility class for determining the JPA {@link AccessType} for an entity.
 * <p>
 * It follows the rules defined in the JPA specification:
 * 1. Explicit {@code @Access} annotation on the class.
 * 2. Inherited class-level {@code @Access} from the superclass chain.
 * 3. Inferred from the placement of {@code @Id} or {@code @EmbeddedId} in the hierarchy.
 * 4. Defaults to {@link AccessType#FIELD} if no other rule applies.
 */
public final class AccessUtils {

    private AccessUtils() {}

    /**
     * Determines the access type for a given entity {@link TypeElement}.
     *
     * @param typeElement The entity class element.
     * @return The determined {@link AccessType}.
     */
    public static AccessType determineAccessType(TypeElement typeElement) {
        // 1) Explicit @Access on the current class.
        Access classAccess = typeElement.getAnnotation(Access.class);
        if (classAccess != null) {
            return classAccess.value();
        }

        // 2) Inherit only class-level @Access from the superclass chain.
        AccessType inherited = findClassLevelAccessInSuperclasses(typeElement);
        if (inherited != null) {
            return inherited;
        }

        // 3) Infer based on the placement of @Id/@EmbeddedId in the hierarchy.
        AccessType inferred = findFirstIdAccessTypeInHierarchy(typeElement);
        return inferred != null ? inferred : AccessType.FIELD;
    }

    /**
     * Finds and inherits only the explicit class-level @Access from superclasses.
     */
    private static AccessType findClassLevelAccessInSuperclasses(TypeElement typeElement) {
        TypeElement current = getSuperclass(typeElement);
        while (current != null && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            Access access = current.getAnnotation(Access.class);
            if (access != null) {
                return access.value();
            }
            current = getSuperclass(current);
        }
        return null;
    }

    /**
     * Scans the entire hierarchy for the location (field/method) of @Id/@EmbeddedId
     * and determines the access type from the first match.
     */
    private static AccessType findFirstIdAccessTypeInHierarchy(TypeElement typeElement) {
        for (TypeElement current = typeElement; 
             current != null && !"java.lang.Object".equals(current.getQualifiedName().toString()); 
             current = getSuperclass(current)) {

            // @Id/@EmbeddedId on a field?
            if (hasIdOnFields(current)) {
                return AccessType.FIELD;
            }
            // @Id/@EmbeddedId on a method (getter)?
            if (hasIdOnMethods(current)) {
                return AccessType.PROPERTY;
            }
        }
        // Not found anywhere -> return null (the caller will fall back to FIELD).
        return null;
    }

    private static boolean hasIdOnFields(TypeElement typeElement) {
        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD && hasIdAnnotation(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIdOnMethods(TypeElement typeElement) {
        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.METHOD
                && isGetterMethod(element)
                && hasIdAnnotation(element)) {
                return true;
            }
        }
        return false;
    }


    public static boolean hasIdAnnotation(Element element) {
        return element != null && (element.getAnnotation(jakarta.persistence.Id.class) != null ||
               element.getAnnotation(jakarta.persistence.EmbeddedId.class) != null);
    }

    public static boolean isGetterMethod(Element element) {
        if (!(element instanceof ExecutableElement method)) return false;
        String name = method.getSimpleName().toString();
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.STATIC) ||
            !(modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) ||
            !method.getParameters().isEmpty() ||
            method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
            return false;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return !"getClass".equals(name);
        }
        if (name.startsWith("is") && name.length() > 2) {
            String returnType = method.getReturnType().toString();
            return "boolean".equals(returnType) || "java.lang.Boolean".equals(returnType);
        }
        return false;
    }

    public static TypeElement getSuperclass(TypeElement typeElement) {
        if (typeElement.getSuperclass() instanceof javax.lang.model.type.DeclaredType declaredType) {
            Element superElement = declaredType.asElement();
            if (superElement instanceof TypeElement) {
                return (TypeElement) superElement;
            }
        }
        return null;
    }
}
