---
title: Relationship and FK Handling
description: How Jinx resolves ownership, joins, and foreign keys
---

# Relationship and FK Handling

Jinx resolves JPA relationships at compile time and turns them into table, join, and foreign-key metadata.

## Main structure

`RelationshipHandler` coordinates relationship processing.

It builds a processor chain in this order:

1. `InverseRelationshipProcessor`
2. `ToOneRelationshipProcessor`
3. `OneToManyOwningFkProcessor`
4. `OneToManyOwningJoinTableProcessor`
5. `ManyToManyOwningProcessor`

The first processor that supports an attribute handles it.

## How attributes are scanned

`RelationshipHandler.resolveRelationships()` first tries cached `AttributeDescriptor`s from `ProcessingContext`.

If the cache is unavailable, it falls back to `@Access`-based scanning:

- field access: scan fields only
- property access: scan getter methods only

This avoids mixing field and property mappings.

## Ownership rules

Jinx follows the owning-side rules from the code:

- `mappedBy` means inverse side
- `@ManyToOne` is always owning side
- `@OneToOne` without `mappedBy` is owning side
- owning `@OneToMany` can use either foreign keys or a join table
- owning `@ManyToMany` creates a join table

Inverse sides do not generate foreign keys on their own.

## Foreign key creation

For owning to-one relations, Jinx creates FK columns from the relationship attribute and the target primary key.

By default, the FK column name comes from:

`foreignKeyColumnName(ownerName, referencedPkColumnName)`

In `DefaultNaming`, that becomes:

`<owner>_<referenced_pk>`

`@JoinColumn` and `@JoinColumns` can override the default names.

## Join tables

Join tables are used for:

- owning `@ManyToMany`
- `@OneToMany` mappings that are configured to use a join table
- some secondary-table and inheritance join cases handled elsewhere in entity processing

The default join table name is created by `DefaultNaming.joinTableName(leftTable, rightTable)`, which:

- normalizes both table names
- sorts them alphabetically
- builds `jt_<a>__<b>`
- truncates with a stable hash if the name is too long

## `mappedBy` safety

Bidirectional lookups can recurse.  
Jinx uses `ProcessingContext.mappedByVisitedSet` to break cycles while resolving inverse relationships.

## `@MapsId`

`@MapsId` is processed in a deferred step after regular relationships are created.

`RelationshipHandler.processMapsIdAttributes()` checks that:

- the attribute is an owning `@ManyToOne` or owning `@OneToOne`
- the owner already has primary-key columns
- the relationship model already exists
- the FK lives on the owner primary table

Then Jinx matches FK columns to either:

- the full primary key, or
- one primary-key attribute path from `@MapsId("...")`

That mapping is recorded in `pkAttributeToColumnMap` inside `ProcessingContext`.
