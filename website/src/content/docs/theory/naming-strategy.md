---
title: Naming Strategy
description: How Jinx builds stable names for tables, columns, and constraints
---

# Naming Strategy

Jinx uses the `Naming` interface and the default implementation `DefaultNaming` to generate database object names.

## What it names

The naming layer covers:

- join tables
- foreign-key columns
- primary keys
- foreign keys
- unique constraints
- indexes
- check constraints
- not-null constraints
- default constraints
- auto-generated constraints

## Core rules

`DefaultNaming` uses a small set of predictable patterns:

- join table: `jt_<a>__<b>`
- foreign-key column: `<owner>_<referenced_pk>`
- foreign-key name: `fk_<child_table>__<child_cols>__<parent_table>`
- primary-key name: `pk_<table>__<cols>`
- unique name: `uq_<table>__<cols>`
- index name: `ix_<table>__<cols>`
- check name: `ck_<table>__<cols>` or `ck_<table>__<constraint>`
- not-null name: `nn_<table>__<cols>`
- default name: `df_<table>__<cols>`
- auto name: `cn_<table>__<cols>`

## Normalization

Before building a name, Jinx normalizes each part:

- `null` becomes `null`
- non-alphanumeric characters become `_`
- repeated `_` characters are collapsed
- names are lowercased
- empty results become `x`

## Column ordering

For names that include multiple columns, Jinx:

1. normalizes each column name
2. sorts them case-insensitively
3. joins them with `_`

This keeps generated names stable regardless of declaration order.

## Length limits

If a generated name is longer than the configured limit, `DefaultNaming` shortens it and appends a stable hash.

The result is:

`<trimmed_prefix>_<hash>`

The hash is based on SHA-256 and uses the first 8 hex characters.

## Where configuration happens

`JpaSqlGeneratorProcessor` creates the naming setup during initialization.

It reads:

- `jinx.naming.strategy`
- `jinx.naming.maxLength`

The processor then stores:

- a `JinxNamingStrategy` for physical table-name transformation
- a `DefaultNaming` instance for generated database object names

So table names and constraint names are related, but they are not produced by the same API.
