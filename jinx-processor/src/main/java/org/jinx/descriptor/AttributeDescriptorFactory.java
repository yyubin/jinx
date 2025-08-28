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

    /**
     * Create an AttributeDescriptorFactory with the given type/element utilities and processing context.
     *
     * The constructor stores the provided utilities and retrieves a Messager from the ProcessingContext
     * for diagnostic reporting during descriptor creation.
     */
    public AttributeDescriptorFactory(Types typeUtils, Elements elements, ProcessingContext context) {
        this.typeUtils = typeUtils;
        this.elements = elements;
        this.context = context;
        this.messager = context.getMessager();
    }

    /**
     * Create AttributeDescriptors for all persistent attributes of the given entity or mapped superclass.
     *
     * <p>Inspects the class hierarchy to collect field/getter candidates, determines the effective JPA
     * access type for the type, and resolves each candidate to either a field or property descriptor
     * according to JPA access rules and mapping annotations. Conflicts (e.g., conflicting @Access or
     * mapping annotations on both field and getter) are reported and those candidates are skipped.</p>
     *
     * @param typeElement the entity or mapped superclass to analyze
     * @return a list of resolved AttributeDescriptor instances (empty if none were resolved)
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
         * Choose a FieldAttributeDescriptor or PropertyAttributeDescriptor for a logical attribute
         * according to JPA access rules, explicit @Access annotations, and the presence of
         * mapping annotations on the field or getter.
         *
         * <p>Selection rules (summarized):
         * - Conflicting @Access on both field and getter -> reports an error and returns empty.
         * - Explicit @Access on a member overrides the default access type; if the required
         *   counterpart (field or getter) is missing an error is reported and empty is returned.
         * - If both field and getter carry JPA mapping annotations -> reports an error and returns empty.
         * - An explicit mapping annotation on a member selects that member.
         * - Otherwise, use the provided default access type (PROPERTY when a getter exists),
         *   then fallback to field, then to getter.
         *
         * Side effects: reports errors to the processing Messager for conflicting or invalid mappings.
         *
         * @param candidate the attribute candidate containing the optional field and/or getter;
         *                  parameter name and presence of members are used to resolve selection.
         * @param defaultAccessType the entity's effective default AccessType used when no explicit
         *                          annotations disambiguate member selection
         * @return an Optional containing the chosen AttributeDescriptor, or Optional.empty() if
         *         selection failed due to conflicts or missing required members
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
         * Returns true if the given element is annotated with any of the standard JPA mapping
         * annotations defined in MAPPING_ANNOTATIONS.
         *
         * @param element the element to inspect; may be null
         * @return {@code true} if the element has at least one mapping annotation, {@code false} otherwise
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

    /**
     * Collects persistent attribute candidates from the given type and its superclass hierarchy.
     *
     * Recurses into superclasses first so attributes declared in subclasses override those from
     * superclasses. Only types annotated with `@Entity` or `@MappedSuperclass` are processed.
     * For each non-static, accessible enclosed element:
     * - if it's a field, the field is recorded under the field's simple name;
     * - if it's a getter method (per `isGetterMethod`), the method is recorded under the derived
     *   attribute name (as returned by `extractAttributeName`).
     *
     * @param typeElement the type whose attributes (and its supertypes') should be collected; may be null
     * @param candidates  map of attribute name → AttributeCandidate that will be populated or updated;
     *                    existing entries are preserved and updated so subclass members override parents
     */
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
        
        /**
 * Creates an AttributeCandidate for the given logical attribute name.
 *
 * @param name the logical attribute name (e.g., a field name or a JavaBean property name) used to group a field and/or getter while collecting attributes from the type hierarchy
 */
public AttributeCandidate(String name) { this.name = name; }
        /**
 * Returns the attribute name.
 *
 * @return the attribute's name
 */
public String getName() { return name; }
        /**
 * Returns the backing field element for this attribute candidate.
 *
 * @return the {@link VariableElement} representing the field, or {@code null} if no field was recorded
 */
public VariableElement getField() { return field; }
        /**
 * Returns the stored getter method for this attribute candidate.
 *
 * @return the getter {@code ExecutableElement}, or {@code null} if no getter is recorded
 */
public ExecutableElement getGetter() { return getter; }
        /**
 * Set the backing field element for this attribute candidate.
 *
 * @param field the VariableElement representing the attribute's field; may be {@code null} to clear the field association
 */
public void setField(VariableElement field) { this.field = field; }
        /**
 * Sets the getter method associated with this attribute candidate.
 *
 * @param getter the getter method (ExecutableElement); may be null to clear the association
 */
public void setGetter(ExecutableElement getter) { this.getter = getter; }
    }

    /**
     * Derives the JavaBean property name from a getter method name.
     *
     * <p>Converts method names of the forms `getXxx...` and `isXxx...` to `xxx...` using
     * `java.beans.Introspector.decapitalize`. If the name does not match those prefixes,
     * the method name is decapitalized directly.
     *
     * @param methodName the method name (expected a getter-like name, e.g. "getName" or "isActive")
     * @return the inferred attribute/property name according to JavaBean naming conventions
     */
    private String extractAttributeName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return java.beans.Introspector.decapitalize(methodName.substring(3));
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            return java.beans.Introspector.decapitalize(methodName.substring(2));
        }
        return java.beans.Introspector.decapitalize(methodName);
    }

    /**
     * Determine the JPA access type to use for the given class.
     *
     * <p>Resolution follows JPA rules in order:
     * <ol>
     *   <li>If the class is annotated with `@Access`, that value is returned.</li>
     *   <li>Otherwise, the superclass chain is consulted recursively for an explicit `@Access`.</li>
     *   <li>If no explicit access type is found, the access type is inferred from the placement
     *       of `@Id`/`@EmbeddedId` in the class hierarchy.</li>
     * </ol>
     *
     * @param typeElement the type element representing an entity or mapped superclass to inspect
     * @return the resolved {@code AccessType} for the class (never {@code null})
     */
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

    /**
     * Infers the effective JPA access type for the given type by locating the first
     * occurrence of an `@Id` or `@EmbeddedId` in the class hierarchy and using its
     * placement (field → FIELD, getter → PROPERTY).
     *
     * @param typeElement the type whose hierarchy is searched (entity or mapped superclass)
     * @return the inferred AccessType based on the placement of the first id; defaults to FIELD if none found
     */
    private AccessType inferAccessTypeFromIdPlacementInHierarchy(TypeElement typeElement) {
        return findFirstIdAccessTypeInHierarchy(typeElement);
    }
    
    /**
     * Finds the effective AccessType by scanning the class hierarchy (root → current) for the first
     * occurrence of an `@Id` or `@EmbeddedId` and returning FIELD if that id is declared on a field
     * or PROPERTY if declared on a getter.
     *
     * If no id annotation is found anywhere in the hierarchy, this method defaults to {@link AccessType#FIELD}.
     *
     * @param typeElement the type element whose hierarchy will be scanned (may be an entity or mapped superclass)
     * @return the AccessType determined by the placement of the first id in the hierarchy, or {@code FIELD} if none found
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
    

    /**
     * Determines whether this class declares its primary key (`@Id` / `@EmbeddedId`) on a field or on a getter.
     *
     * <p>Only inspects the supplied type if it is annotated with `@Entity` or `@MappedSuperclass`. Scans the
     * type's enclosed elements and returns {@link AccessType#FIELD} if an `@Id`/`@EmbeddedId` is found on a field,
     * or {@link AccessType#PROPERTY} if found on a getter method. Returns {@code null} if the type is not an
     * entity/mapped-superclass or if no id annotation is present on the class.</p>
     *
     * @param typeElement the type to inspect (expected to be a class or interface element)
     * @return {@link AccessType#FIELD}, {@link AccessType#PROPERTY}, or {@code null} if none applies
     */
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

    /**
     * Returns true if the given element is annotated with `@Id` or `@EmbeddedId`.
     *
     * @param element the element to inspect (may be null)
     * @return true when {@code element} is non-null and has either `jakarta.persistence.Id` or `jakarta.persistence.EmbeddedId`; false otherwise
     */
    private boolean hasIdAnnotation(Element element) {
        return element != null && (element.getAnnotation(jakarta.persistence.Id.class) != null ||
               element.getAnnotation(jakarta.persistence.EmbeddedId.class) != null);
    }

    /**
     * Determines whether the given element is a JavaBean-style getter method.
     *
     * A valid getter is a non-static, public or protected method with no parameters and a non-void
     * return type that follows one of these naming/return-type patterns:
     * - "getXxx" (length > 3), excluding "getClass"
     * - "isXxx" (length > 2) with return type `boolean` or `java.lang.Boolean`
     *
     * @param element the element to evaluate (expected to be an ExecutableElement)
     * @return true if the element matches getter conventions, false otherwise
     */
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

    /**
     * Returns true if the given element should be considered for persistence mapping.
     *
     * <p>An element is considered accessible when it is non-null, not declared with the
     * `transient` modifier, and not annotated with `jakarta.persistence.Transient`.</p>
     *
     * @param element the field or method element to check for persistence accessibility
     * @return true if the element is eligible for mapping; false otherwise
     */
    private boolean isAccessible(Element element) {
        return element != null &&
               !element.getModifiers().contains(Modifier.TRANSIENT) &&
               element.getAnnotation(jakarta.persistence.Transient.class) == null;
    }

    /**
     * Returns the direct superclass as a TypeElement, or null if there is no declared superclass.
     *
     * The method checks the element's superclass and converts it to a TypeElement when the superclass
     * is a declared (non-primitive, non-array) type; otherwise it returns null.
     *
     * @param typeElement the type whose superclass is being queried
     * @return the superclass as a TypeElement, or null if the superclass is not a declared type or does not exist
     */
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
