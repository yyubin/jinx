---
title: JPA Processing
description: How Jinx reads JPA annotations at compile time
---

# JPA Processing

Jinx parses JPA metadata during annotation processing and builds a schema model before writing migration data.

## Main flow

`JpaSqlGeneratorProcessor` is the entry point.

It initializes a shared `ProcessingContext` and these handlers:

- `EntityHandler`
- `RelationshipHandler`
- `InheritanceHandler`
- `SequenceHandler`
- `EmbeddedHandler`
- `ConstraintHandler`
- `ElementCollectionHandler`
- `TableGeneratorHandler`

For each processing round, Jinx:

1. Resets round-level caches in `ProcessingContext`
2. Registers `@Converter(autoApply = true)` converters
3. Registers `@MappedSuperclass` and `@Embeddable`
4. Processes each `@Entity`
5. Resolves inheritance
6. Validates primary keys
7. Retries deferred work such as `JOINED` inheritance, missing relationship targets, `@ElementCollection`, and `@MapsId`
8. Writes the final schema JSON

## Shared context

`ProcessingContext` stores the schema model and the state shared by all handlers.

Important parts:

- `descriptorCache`: caches `AttributeDescriptor` lists per type
- `mappedSuperclassElements` and `embeddableElements`: round-local type registries
- `deferredEntities` and `deferredNames`: retry queue for unresolved work
- `pkAttributeToColumnMap`: used for `@MapsId("...")`
- `mappedByVisitedSet`: prevents recursive `mappedBy` loops

## Attribute discovery

Jinx does not read fields and getters separately for every handler. It first builds `AttributeDescriptor`s with `AttributeDescriptorFactory`.

The factory:

- determines the default access type with `AccessUtils.determineAccessType()`
- walks the class hierarchy
- includes attributes from `@MappedSuperclass`
- stops climbing when it reaches an `@Entity` superclass, so child tables do not duplicate parent table columns
- prefers explicit `@Access`
- otherwise chooses the side that has JPA mapping annotations
- falls back to the entity default access type

This keeps field access, property access, and record components consistent.

## Entity processing

`EntityHandler` processes one entity in this order:

1. Create and pre-register `EntityModel`
2. Read table metadata
3. Process sequence and table generators
4. Process entity-level constraints
5. Register secondary tables
6. Process composite keys such as `@EmbeddedId`
7. Process attributes through `AttributeDescriptorFactory`
8. Process secondary-table joins
9. Process `JOINED` inheritance joins
10. Queue deferred `@MapsId` work if needed

Pre-registration matters because related entities may reference each other before both are fully processed.

## Why deferred processing exists

Some metadata cannot be finalized on the first pass.

Examples:

- a `JOINED` child may need a parent primary key that is not ready yet
- a relationship may point to an entity processed later
- `@ElementCollection` may depend on the owner primary key
- `@MapsId` needs both primary-key and foreign-key metadata

Jinx re-runs these cases from the deferred queue until dependencies are resolved or no progress is possible.
