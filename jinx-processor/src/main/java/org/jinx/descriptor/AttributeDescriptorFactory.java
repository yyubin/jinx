package org.jinx.descriptor;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.jinx.context.ProcessingContext;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Factory for creating AttributeDescriptor instances based on JPA Access strategy.
 * This factory enforces rules for handling mixed access (FIELD and PROPERTY) and
 * reports errors on conflicting mapping declarations.
 */
public class AttributeDescriptorFactory {
    private final Types typeUtils;
    private final Elements elements;
    private final ProcessingContext context;
    private final Messager messager;

    // Set of common JPA mapping annotations to check for conflicts.
    private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS = Set.of(
        jakarta.persistence.Column.class,
        jakarta.persistence.JoinColumn.class,
        jakarta.persistence.JoinColumns.class,
        jakarta.persistence.OneToOne.class,
        jakarta.persistence.OneToMany.class,
        jakarta.persistence.ManyToOne.class,
        jakarta.persistence.ManyToMany.class,
        jakarta.persistence.ElementCollection.class,
        jakarta.persistence.Embedded.class,
        jakarta.persistence.EmbeddedId.class,
        jakarta.persistence.Id.class,
        jakarta.persistence.MapsId.class,
        jakarta.persistence.Lob.class,
        jakarta.persistence.OrderBy.class,
        jakarta.persistence.OrderColumn.class,
        jakarta.persistence.JoinTable.class,
        jakarta.persistence.AssociationOverride.class,
        jakarta.persistence.AssociationOverrides.class,
        jakarta.persistence.AttributeOverride.class,
        jakarta.persistence.AttributeOverrides.class
    );

    public AttributeDescriptorFactory(Types typeUtils, Elements elements, ProcessingContext context) {
        this.typeUtils = typeUtils;
        this.elements = elements;
        this.context = context;
        this.messager = context.getMessager();
    }

    /**
     * Creates AttributeDescriptors for all persistent attributes in a given TypeElement.
     * It determines the default access type, collects all potential attributes (fields and getters),
     * and resolves any conflicts or ambiguities based on JPA rules.
     *
     * @param typeElement The entity or mapped superclass to process.
     * @return A list of valid AttributeDescriptors.
     */
    public List<AttributeDescriptor> createDescriptors(TypeElement typeElement) {
        AccessType defaultAccessType = determineAccessType(typeElement);
        List<AttributeDescriptor> descriptors = new ArrayList<>();

        Map<String, AttributeCandidate> attributeCandidates = collectAttributeCandidates(typeElement);

        for (AttributeCandidate candidate : attributeCandidates.values()) {
            selectAttributeDescriptor(candidate, defaultAccessType)
                .ifPresent(descriptors::add);
        }

        return descriptors;
    }

    /**
     * Selects the appropriate descriptor (field or property) for a logical attribute
     * based on explicit annotations and the default access type.
     * It also validates against conflicting annotations on both the field and the getter.
     *
     * @param candidate The attribute candidate holding the field and/or getter.
     * @param defaultAccessType The default access type for the entity.
     * @return An Optional containing the chosen AttributeDescriptor, or empty if none is chosen.
     */
    private Optional<AttributeDescriptor> selectAttributeDescriptor(AttributeCandidate candidate, AccessType defaultAccessType) {
        VariableElement field = candidate.getField();
        ExecutableElement getter = candidate.getGetter();

        Access fieldAccess = (field != null) ? field.getAnnotation(Access.class) : null;
        Access getterAccess = (getter != null) ? getter.getAnnotation(Access.class) : null;

        // Rule: Conflicting @Access annotations on both field and getter is an error.
        if (fieldAccess != null && getterAccess != null) {
            messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(
                    "Conflicting @Access annotations on both field '%s' and getter '%s' for property '%s'.",
                    field.getSimpleName(),
                    getter.getSimpleName(),
                    candidate.getName()
                ),
                field // Report on field
            );
            return Optional.empty();
        }

        // Rule: Explicit @Access on a member overrides the default access type.
        if (fieldAccess != null) {
            if (fieldAccess.value() == AccessType.FIELD) {
                return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
            } else { // AccessType.PROPERTY
                if (getter == null) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "No getter found for property '" + candidate.getName() + "' which is explicitly marked for property access.", field);
                    return Optional.empty();
                }
                return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
            }
        }

        if (getterAccess != null) {
            if (getterAccess.value() == AccessType.PROPERTY) {
                return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
            } else { // AccessType.FIELD
                if (field == null) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "No field found for property '" + candidate.getName() + "' which is explicitly marked for field access.", getter);
                    return Optional.empty();
                }
                return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
            }
        }

        boolean fieldHasMapping = hasMappingAnnotation(field);
        boolean getterHasMapping = hasMappingAnnotation(getter);

        // Rule: If both field and getter have mapping annotations, it's a conflict. Report error.
        if (fieldHasMapping && getterHasMapping) {
            messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(
                    "Conflicting JPA mapping annotations on both field '%s' and getter '%s' for property '%s'. " +
                    "Remove the annotation from one of them to resolve ambiguity.",
                    field.getSimpleName(),
                    getter.getSimpleName(),
                    candidate.getName()
                ),
                field != null ? field : getter // Safely select a non-null element for reporting
            );
            return Optional.empty();
        }

        // Rule: Explicit mapping annotation on the field takes precedence.
        if (fieldHasMapping) {
            return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
        }

        // Rule: Explicit mapping annotation on the getter takes precedence.
        if (getterHasMapping) {
            return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
        }

        // Rule: No explicit annotations, so use the default access type.
        if (defaultAccessType == AccessType.PROPERTY && getter != null) {
            return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
        }

        // Default to FIELD access if no property access is defined or if it's the default.
        if (field != null) {
            return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
        }
        
        // Fallback for property-only cases when default is field but no field exists.
        if (getter != null) {
             return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
        }

        return Optional.empty();
    }

    /**
     * Checks if an element has any of the standard JPA mapping annotations.
     *
     * @param element The element to check (can be null).
     * @return true if a mapping annotation is present, false otherwise.
     */
    private boolean hasMappingAnnotation(Element element) {
        if (element == null) {
            return false;
        }
        for (Class<? extends Annotation> annotation : MAPPING_ANNOTATIONS) {
            if (element.getAnnotation(annotation) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects all fields and getter methods from a TypeElement and its @MappedSuperclass hierarchy
     * and groups them by logical attribute name into AttributeCandidate objects.
     * Attributes from subclasses override those from superclasses.
     */
    private Map<String, AttributeCandidate> collectAttributeCandidates(TypeElement typeElement) {
        Map<String, AttributeCandidate> candidates = new HashMap<>();
        collectAttributesFromHierarchy(typeElement, candidates);
        return candidates;
    }

    private void collectAttributesFromHierarchy(TypeElement typeElement, Map<String, AttributeCandidate> candidates) {
        if (typeElement == null || "java.lang.Object".equals(typeElement.getQualifiedName().toString())) {
            return;
        }

        // Recurse to parent first, so that child attributes override parent attributes.
        collectAttributesFromHierarchy(getSuperclass(typeElement), candidates);

        // We only collect attributes from entities and mapped superclasses, as per JPA specification.
        boolean isEntity = typeElement.getAnnotation(jakarta.persistence.Entity.class) != null;
        boolean isMappedSuperclass = typeElement.getAnnotation(jakarta.persistence.MappedSuperclass.class) != null;

        if (isEntity || isMappedSuperclass) {
            for (Element element : typeElement.getEnclosedElements()) {
                if (element.getModifiers().contains(Modifier.STATIC) || !isAccessible(element)) {
                    continue;
                }

                if (element.getKind() == ElementKind.FIELD) {
                    VariableElement field = (VariableElement) element;
                    String attributeName = field.getSimpleName().toString();
                    candidates.computeIfAbsent(attributeName, AttributeCandidate::new)
                            .setField(field);
                } else if (element.getKind() == ElementKind.METHOD && isGetterMethod(element)) {
                    ExecutableElement getter = (ExecutableElement) element;
                    String attributeName = extractAttributeName(getter.getSimpleName().toString());
                    candidates.computeIfAbsent(attributeName, AttributeCandidate::new)
                            .setGetter(getter);
                }
            }
        }
    }
    
    // --- Helper and JPA Spec-compliant Access Type Determination Methods ---

    private static class AttributeCandidate {
        private final String name;
        private VariableElement field;
        private ExecutableElement getter;
        
        public AttributeCandidate(String name) { this.name = name; }
        public String getName() { return name; }
        public VariableElement getField() { return field; }
        public ExecutableElement getGetter() { return getter; }
        public void setField(VariableElement field) { this.field = field; }
        public void setGetter(ExecutableElement getter) { this.getter = getter; }
    }

    private String extractAttributeName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return java.beans.Introspector.decapitalize(methodName.substring(3));
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            return java.beans.Introspector.decapitalize(methodName.substring(2));
        }
        return java.beans.Introspector.decapitalize(methodName);
    }

    private AccessType determineAccessType(TypeElement typeElement) {
        // This implementation should follow the JPA specification:
        // 1. Explicit @Access on the class.
        // 2. Explicit @Access on a superclass.
        // 3. Inferred from the placement of @Id in the hierarchy root.
        // (Assuming the existing implementation is correct per JPA spec)
        
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

    private AccessType inferAccessTypeFromIdPlacementInHierarchy(TypeElement typeElement) {
        return findFirstIdAccessTypeInHierarchy(typeElement);
    }
    
    /**
     * Traverse the hierarchy from root to current type to find the first @Id/@EmbeddedId annotation.
     * According to JPA specification, the placement of the first encountered @Id determines the AccessType.
     */
    private AccessType findFirstIdAccessTypeInHierarchy(TypeElement typeElement) {
        // Build hierarchy path from root to current (top-down)
        List<TypeElement> hierarchy = buildHierarchyPath(typeElement);
        
        // Search for first @Id/@EmbeddedId from root to current
        for (TypeElement type : hierarchy) {
            AccessType accessType = findIdAccessTypeInClass(type);
            if (accessType != null) {
                return accessType;
            }
        }
        
        // Default to FIELD if no @Id found in the entire hierarchy
        return AccessType.FIELD;
    }
    
    /**
     * Builds a list of TypeElements from hierarchy root to the given type (inclusive).
     * The list is ordered from root to descendant.
     */
    private List<TypeElement> buildHierarchyPath(TypeElement typeElement) {
        List<TypeElement> path = new ArrayList<>();
        TypeElement current = typeElement;
        
        // Build path from current to root
        while (current != null && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            path.add(current);
            current = getSuperclass(current);
        }
        
        // Reverse to get root-to-current order
        Collections.reverse(path);
        return path;
    }
    

    private AccessType findIdAccessTypeInClass(TypeElement typeElement) {
        // Only check Entity and MappedSuperclass types for @Id placement
        boolean isEntity = typeElement.getAnnotation(jakarta.persistence.Entity.class) != null;
        boolean isMappedSuperclass = typeElement.getAnnotation(jakarta.persistence.MappedSuperclass.class) != null;
        
        if (!isEntity && !isMappedSuperclass) {
            return null; // Skip non-JPA types in hierarchy
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

    private boolean hasIdAnnotation(Element element) {
        return element != null && (element.getAnnotation(jakarta.persistence.Id.class) != null ||
               element.getAnnotation(jakarta.persistence.EmbeddedId.class) != null);
    }

    private boolean isGetterMethod(Element element) {
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

    private boolean isAccessible(Element element) {
        return element != null &&
               !element.getModifiers().contains(Modifier.TRANSIENT) &&
               element.getAnnotation(jakarta.persistence.Transient.class) == null;
    }

    private TypeElement getSuperclass(TypeElement typeElement) {
        if (typeElement.getSuperclass() instanceof javax.lang.model.type.DeclaredType declaredType) {
            Element superElement = declaredType.asElement();
            if (superElement instanceof TypeElement) {
                return (TypeElement) superElement;
            }
        }
        return null;
    }
}
