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
                return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
            }
        }
        if (getterAccess != null) {
            if (getterAccess.value() == AccessType.PROPERTY) {
                return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
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
            return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
        }
        if (defaultAccessType == AccessType.PROPERTY && getter != null) {
            return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
        }
        if (field != null) {
            return Optional.of(new FieldAttributeDescriptor(field, typeUtils, elements));
        }
        if (getter != null) {
             return Optional.of(new PropertyAttributeDescriptor(getter, typeUtils, elements));
        }
        return Optional.empty();
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