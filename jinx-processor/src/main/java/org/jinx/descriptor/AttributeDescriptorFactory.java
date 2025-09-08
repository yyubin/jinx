package org.jinx.descriptor;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.jinx.context.ProcessingContext;
import org.jinx.util.AccessUtils;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.*;

public class AttributeDescriptorFactory {
    private final Types typeUtils;
    private final Elements elements;
    private final ProcessingContext context;
    private final Messager messager;

    private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS = Set.of(
        jakarta.persistence.Basic.class,
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
        jakarta.persistence.AttributeOverrides.class,
        jakarta.persistence.Convert.class,
        jakarta.persistence.Enumerated.class,
        jakarta.persistence.Temporal.class,
        jakarta.persistence.GeneratedValue.class,
        jakarta.persistence.Version.class
    );

    public AttributeDescriptorFactory(Types typeUtils, Elements elements, ProcessingContext context) {
        this.typeUtils = typeUtils;
        this.elements = elements;
        this.context = context;
        this.messager = context.getMessager();
    }

    public List<AttributeDescriptor> createDescriptors(TypeElement typeElement) {
        AccessType defaultAccessType = AccessUtils.determineAccessType(typeElement);
        List<AttributeDescriptor> descriptors = new ArrayList<>();
        Map<String, AttributeCandidate> attributeCandidates = collectAttributeCandidates(typeElement);
        for (AttributeCandidate candidate : attributeCandidates.values()) {
            selectAttributeDescriptor(candidate, defaultAccessType)
                .ifPresent(descriptors::add);
        }
        return descriptors;
    }

    private Optional<AttributeDescriptor> selectAttributeDescriptor(AttributeCandidate candidate, AccessType defaultAccessType) {
        VariableElement field = candidate.getField();
        ExecutableElement getter = candidate.getGetter();
        Access fieldAccess = (field != null) ? field.getAnnotation(Access.class) : null;
        Access getterAccess = (getter != null) ? getter.getAnnotation(Access.class) : null;
        if (fieldAccess != null && getterAccess != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, String.format("Conflicting @Access annotations on both field '%s' and getter '%s' for property '%s'.", field.getSimpleName(), getter.getSimpleName(), candidate.getName()), field);
            return Optional.empty();
        }
        if (fieldAccess != null) {
            if (fieldAccess.value() == AccessType.FIELD) {
                return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
            } else {
                if (getter == null) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "No getter found for property '" + candidate.getName() + "' which is explicitly marked for property access.", field);
                    return Optional.empty();
                }
                return createPropertyDescriptor(getter);
            }
        }
        if (getterAccess != null) {
            if (getterAccess.value() == AccessType.PROPERTY) {
                return createPropertyDescriptor(getter);
            } else {
                if (field == null) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "No field found for property '" + candidate.getName() + "' which is explicitly marked for field access.", getter);
                    return Optional.empty();
                }
                return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
            }
        }
        boolean fieldHasMapping = hasMappingAnnotation(field);
        boolean getterHasMapping = hasMappingAnnotation(getter);
        if (fieldHasMapping && getterHasMapping) {
            messager.printMessage(Diagnostic.Kind.ERROR, String.format("Conflicting JPA mapping annotations on both field '%s' and getter '%s' for property '%s'. Remove the annotation from one of them to resolve ambiguity.", field.getSimpleName(), getter.getSimpleName(), candidate.getName()), field != null ? field : getter);
            return Optional.empty();
        }
        if (fieldHasMapping) {
            return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
        }
        if (getterHasMapping) {
            return createPropertyDescriptor(getter);
        }
        if (defaultAccessType == AccessType.PROPERTY && getter != null) {
            return createPropertyDescriptor(getter);
        }
        if (field != null) {
            return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
        }
        if (getter != null) {
            return createPropertyDescriptor(getter);
        }
        return Optional.empty();
    }
    
    /**
     * Safely creates a PropertyAttributeDescriptor, validating the getter first
     * and reporting errors via Messager instead of letting exceptions propagate.
     */
    public Optional<AttributeDescriptor> createPropertyDescriptor(ExecutableElement getter) {
        if (getter == null) {
            return Optional.empty();
        }
        
        if (!PropertyAttributeDescriptor.isValidGetter(getter)) {
            String methodName = getter.getSimpleName().toString();
            String errorMessage = getGetterValidationErrorMessage(getter, methodName);
            messager.printMessage(Diagnostic.Kind.ERROR, errorMessage, getter);
            return Optional.empty();
        }
        
        return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
    }
    
    private String getGetterValidationErrorMessage(ExecutableElement getter, String methodName) {
        // Must have no parameters
        if (getter.getParameters() != null && !getter.getParameters().isEmpty()) {
            return "Getter must have no parameters: " + getter;
        }
        
        // Must have non-void return type
        if (getter.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
            return "Getter must have a non-void return type: " + getter;
        }
        
        // Exclude getClass() - it's Object method, not a property getter
        if ("getClass".equals(methodName)) {
            return "getClass() is not a valid property getter: " + getter;
        }
        
        // Must start with getXxx or isXxx
        boolean isGet = methodName.startsWith("get") && methodName.length() > 3;
        boolean isIs  = methodName.startsWith("is")  && methodName.length() > 2;
        if (!isGet && !isIs) {
            return "Method is not a valid JavaBeans getter: " + getter;
        }
        
        // Validate isXxx methods must return boolean/Boolean
        if (methodName.startsWith("is") && methodName.length() > 2) {
            javax.lang.model.type.TypeKind rk = getter.getReturnType().getKind();
            String returnTypeName = getter.getReturnType().toString();
            if (!(rk == javax.lang.model.type.TypeKind.BOOLEAN || "java.lang.Boolean".equals(returnTypeName))) {
                return "isXxx getter must return boolean or Boolean, but returns " + 
                    returnTypeName + ": " + getter;
            }
        }
        
        // Validate proper capitalization after prefix
        if (methodName.startsWith("get") && methodName.length() > 3) {
            char firstChar = methodName.charAt(3);
            if (!Character.isUpperCase(firstChar)) {
                return "getXxx method must have uppercase character after 'get': " + getter;
            }
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            char firstChar = methodName.charAt(2);
            if (!Character.isUpperCase(firstChar)) {
                return "isXxx method must have uppercase character after 'is': " + getter;
            }
        }
        
        return "Invalid getter: " + getter;
    }

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

    private Map<String, AttributeCandidate> collectAttributeCandidates(TypeElement typeElement) {
        Map<String, AttributeCandidate> candidates = new HashMap<>();
        collectAttributesFromHierarchy(typeElement, candidates);
        return candidates;
    }

    private void collectAttributesFromHierarchy(TypeElement typeElement, Map<String, AttributeCandidate> candidates) {
        if (typeElement == null || "java.lang.Object".equals(typeElement.getQualifiedName().toString())) {
            return;
        }
        collectAttributesFromHierarchy(AccessUtils.getSuperclass(typeElement), candidates);
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
                } else if (element.getKind() == ElementKind.METHOD && AccessUtils.isGetterMethod(element)) {
                    ExecutableElement getter = (ExecutableElement) element;
                    String attributeName = extractAttributeName(getter.getSimpleName().toString());
                    candidates.computeIfAbsent(attributeName, AttributeCandidate::new)
                            .setGetter(getter);
                }
            }
        }
    }
    
    private boolean isAccessible(Element element) {
        return element != null &&
               !element.getModifiers().contains(Modifier.TRANSIENT) &&
               element.getAnnotation(jakarta.persistence.Transient.class) == null;
    }

    private String extractAttributeName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return java.beans.Introspector.decapitalize(methodName.substring(3));
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            return java.beans.Introspector.decapitalize(methodName.substring(2));
        }
        return java.beans.Introspector.decapitalize(methodName);
    }

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
}