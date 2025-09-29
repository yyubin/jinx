---
sidebar_position: 1
---

# Jinx JPA Processing Mechanism

Jinx is a tool that supports DDL migrations by analyzing JPA annotations at compile time using the Annotation Processing Tool (APT). This document covers in detail how JPA annotations are processed within the `jinx-processor` module.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [JPA Annotation Processing](#jpa-annotation-processing)
3. [Caching Mechanism](#caching-mechanism)
4. [Inheritance Handling and BFS Logic](#inheritance-handling-and-bfs-logic)
5. [Primary Key Detection Logic](#primary-key-detection-logic)
6. [Processing Phases](#processing-phases)

## Architecture Overview

### Key Components

Jinx's JPA processing is composed of the following core components:

* **JpaSqlGeneratorProcessor**: Main annotation processor
* **ProcessingContext**: Shared context and cache during processing
* **EntityHandler**: Handles entity processing
* **InheritanceHandler**: Handles inheritance relationships
* **AttributeDescriptorFactory**: Determines field/property access type and generates descriptors
* **Various Handlers**: Handle columns, relationships, constraints, etc.

### Supported JPA Annotations

```java
@SupportedAnnotationTypes({
    "jakarta.persistence.*",
    "org.jinx.annotation.Constraint",
    "org.jinx.annotation.Constraints",
    "org.jinx.annotation.Identity"
})
```

## JPA Annotation Processing

### 1. Annotation Detection and Priority

JPA processing follows this order:

1. **@Converter(autoApply=true)**
2. **@MappedSuperclass** and **@Embeddable**
3. **@Entity**

### 2. Access Type Determination

`AccessUtils.determineAccessType()` determines the entity's default access type.

```java
// Priority:
// 1. Class-level @Access annotation
// 2. Package-level @Access annotation
// 3. Inferred from placement of @Id/@EmbeddedId in hierarchy
// 4. Default: AccessType.FIELD
```

**Hierarchy-based Inference Logic**

```java
private static AccessType findFirstIdAccessTypeInHierarchy(TypeElement typeElement) {
    for (TypeElement current = typeElement;
         current != null && !"java.lang.Object".equals(current.getQualifiedName().toString());
         current = getSuperclass(current)) {

        if (hasIdOnFields(current)) {
            return AccessType.FIELD;
        }
        if (hasIdOnMethods(current)) {
            return AccessType.PROPERTY;
        }
    }
    return null; // fallback to FIELD
}
```

### 3. Attribute Descriptor Generation

`AttributeDescriptorFactory` analyzes fields and properties to decide the correct access type.

#### Mapping Annotation Conflict Check

```java
private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS = Set.of(
    jakarta.persistence.Basic.class,
    jakarta.persistence.Column.class,
    jakarta.persistence.JoinColumn.class
    // ... other JPA mapping annotations
);
```

#### Access Selection Logic

1. **Explicit @Access Priority**: Explicit annotation on field/method takes precedence
2. **Mapping Annotation Based**: Choose the side with JPA mapping annotations
3. **Default AccessType**: Fall back to class-level default

## Caching Mechanism

### 1. ProcessingContext Cache

Jinx uses multiple levels of caching for performance optimization.

```java
public class ProcessingContext {
    private final Map<String, List<AttributeDescriptor>> descriptorCache = new HashMap<>();
    private final Map<String, TypeElement> mappedSuperclassElements = new HashMap<>();
    private final Map<String, TypeElement> embeddableElements = new HashMap<>();
    private final Map<String, Map<String, List<String>>> pkAttributeToColumnMap = new HashMap<>();
    private final Set<String> mappedByVisitedSet = new HashSet<>();
}
```

### 2. Cache Lifecycle

```java
public void beginRound() {
    clearMappedByVisited();
    deferredEntities.clear();
    deferredNames.clear();
    descriptorCache.clear();
    pkAttributeToColumnMap.clear();
    mappedSuperclassElements.clear();
    embeddableElements.clear();
}
```

### 3. AttributeDescriptor Caching

```java
public List<AttributeDescriptor> getCachedDescriptors(TypeElement typeElement) {
    String fqn = typeElement.getQualifiedName().toString();
    return descriptorCache.computeIfAbsent(fqn,
            k -> attributeDescriptorFactory.createDescriptors(typeElement));
}
```

## Inheritance Handling and BFS Logic

### 1. BFS Usage

BFS (Breadth-First Search) is used in these areas:

#### AttributeConverter Type Resolution

```java
private Optional<TypeMirror> findAttributeConverterAttributeType(TypeElement converterType) {
    // BFS queue used to find AttributeConverter<T, ?> type argument
}
```

#### Class Hierarchy Traversal

```java
private void collectAttributesFromHierarchy(TypeElement typeElement, Map<String, AttributeCandidate> candidates) {
    // Recursively traverse superclasses and collect attributes
}
```

### 2. Inheritance Strategies

* **SINGLE_TABLE**: Use discriminator value
* **JOINED**: Map parent PKs to child FKs
* **TABLE_PER_CLASS**: Copy parent columns into child table

### 3. JOINED Strategy Handling

JOINED inheritance requires detailed PK-FK validation and column mapping before applying relationships.

## Primary Key Detection Logic

### 1. PK Annotation Detection

Primary keys are identified via:

* `@Id`
* `@EmbeddedId`

```java
.isPrimaryKey(field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null)
```

### 2. PK Validation Logic

PK checks occur in multiple phases:

* After entity processing
* After JOINED inheritance processing

### 3. @EmbeddedId Handling

Composite PKs are supported through `@EmbeddedId`, while `@IdClass` is not supported.

### 4. PK Attribute-Column Mapping

```java
public void registerPkAttributeColumns(String entityFqcn, String attributePath, List<String> columnNames) { ... }
public List<String> getPkColumnsForAttribute(String entityFqcn, String attributePath) { ... }
```

## Processing Phases

### 1. Initialization Phase

```java
@Override
public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.context = new ProcessingContext(processingEnv, schemaModel);
    this.sequenceHandler = new SequenceHandler(context);
    this.relationshipHandler = new RelationshipHandler(context);
}
```

### 2. Main Processing Phase

```java
@Override
public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (!roundEnv.processingOver()) {
        context.beginRound();
    }

    processRetryTasks();
    // Handle @Converter, @MappedSuperclass, @Embeddable, and @Entity
}
```

### 3. Final Processing Phase

* Resolve inheritance
* Validate PKs
* Process deferred entities (max 5 passes)
* Save JSON schema

### 4. Deferred Processing

Entities with complex dependencies are deferred and retried until resolution is possible.

---

### Key Characteristics of Jinx JPA Processing

1. **Hierarchical Processing**: Structured annotation analysis with inheritance support
2. **Efficient Caching**: Multi-level caching prevents redundant computations
3. **BFS Usage**: Applied in type resolution and hierarchy traversal
4. **Stepwise Validation**: Multi-stage PK validation ensures integrity
5. **Deferred Processing**: Gradual resolution for complex dependencies
6. **Robust Error Handling**: Detailed error reporting for resilient processing