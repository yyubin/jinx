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
        // 1) 현재 클래스에 명시적 @Access
        Access classAccess = typeElement.getAnnotation(Access.class);
        if (classAccess != null) {
            return classAccess.value();
        }

        // 2) 상위 체인의 클래스 레벨 @Access만 상속
        AccessType inherited = findClassLevelAccessInSuperclasses(typeElement);
        if (inherited != null) {
            return inherited;
        }

        // 3) 계층에서 @Id/@EmbeddedId 배치 기반 추론
        AccessType inferred = findFirstIdAccessTypeInHierarchy(typeElement);
        return inferred != null ? inferred : AccessType.FIELD;
    }

    /**
     * 상위 클래스들에서 명시적 클래스 레벨 @Access만 찾아 상속
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
     * 계층 전체에서 @Id/@EmbeddedId 위치(필드/메서드)를 스캔해 첫 일치로 결정
     */
    private static AccessType findFirstIdAccessTypeInHierarchy(TypeElement typeElement) {
        for (TypeElement current = typeElement; 
             current != null && !"java.lang.Object".equals(current.getQualifiedName().toString()); 
             current = getSuperclass(current)) {

            // 필드에 @Id/@EmbeddedId?
            if (hasIdOnFields(current)) {
                return AccessType.FIELD;
            }
            // 메서드(getter)에 @Id/@EmbeddedId?
            if (hasIdOnMethods(current)) {
                return AccessType.PROPERTY;
            }
        }
        // 아무 데도 없음 → null 반환 (호출부에서 FIELD로 폴백)
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
            if (element.getKind() == ElementKind.METHOD && hasIdAnnotation(element)) {
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
