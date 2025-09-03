package org.jinx.util;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;

import javax.lang.model.element.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class AccessUtils {

    private AccessUtils() {}

    public static AccessType determineAccessType(TypeElement typeElement) {
        Access classAccess = typeElement.getAnnotation(Access.class);
        if (classAccess != null) {
            return classAccess.value();
        }

        TypeElement superclass = getSuperclass(typeElement);
        if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName().toString())) {
            AccessType superAccessType = determineAccessType(superclass);
            if (superAccessType != null) {
                return superAccessType;
            }
        }

        return inferAccessTypeFromIdPlacementInHierarchy(typeElement);
    }

    private static AccessType inferAccessTypeFromIdPlacementInHierarchy(TypeElement typeElement) {
        return findFirstIdAccessTypeInHierarchy(typeElement);
    }
    
    private static AccessType findFirstIdAccessTypeInHierarchy(TypeElement typeElement) {
        List<TypeElement> hierarchy = buildHierarchyPath(typeElement);
        
        for (TypeElement type : hierarchy) {
            AccessType accessType = findIdAccessTypeInClass(type);
            if (accessType != null) {
                return accessType;
            }
        }
        
        return AccessType.FIELD;
    }
    
    private static List<TypeElement> buildHierarchyPath(TypeElement typeElement) {
        List<TypeElement> path = new ArrayList<>();
        TypeElement current = typeElement;
        
        while (current != null && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            path.add(current);
            current = getSuperclass(current);
        }
        
        Collections.reverse(path);
        return path;
    }

    private static AccessType findIdAccessTypeInClass(TypeElement typeElement) {
        boolean isEntity = typeElement.getAnnotation(jakarta.persistence.Entity.class) != null;
        boolean isMappedSuperclass = typeElement.getAnnotation(jakarta.persistence.MappedSuperclass.class) != null;
        
        if (!isEntity && !isMappedSuperclass) {
            return null;
        }
        
        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD && hasIdAnnotation(element)) {
                return AccessType.FIELD;
            }
            if (element.getKind() == ElementKind.METHOD && isGetterMethod(element) && hasIdAnnotation(element)) {
                return AccessType.PROPERTY;
            }
        }
        return null;
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
