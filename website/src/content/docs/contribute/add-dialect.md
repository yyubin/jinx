---
title: Add a New Dialect
description: How to add a database dialect to the current Jinx codebase
---

# Add a New Dialect

Jinx can be extended with new database dialects, but in the current codebase this is still a code change, not a plug-and-play module.

Right now, MySQL is the built-in reference implementation.

## What exists today

The main pieces are:

- `MySqlDialect` in `jinx-core`
- `DialectBundle`, which declares which features a dialect supports
- `VisitorFactory`, which selects a `VisitorProvider`
- `MigrateCommand.resolveDialects()`, which maps the CLI `--dialect` value to a `DialectBundle`

If you add a new dialect, you need to update all of those places.

## Minimum implementation

Start by copying the MySQL structure under a new package in `jinx-core`.

In practice, you usually need:

- one main dialect class implementing `DdlDialect`
- `JavaTypeMapper`
- `ValueTransformer`
- one `VisitorProvider`
- table or sequence helpers if the database supports them

Optional capabilities are added through `DialectBundle`:

- `IdentityDialect`
- `SequenceDialect`
- `TableGeneratorDialect`
- `LiquibaseDialect`

## Registration steps

To make the new dialect usable, register it in three places.

1. Add a new dialect class and related helpers in `jinx-core`
2. Add a matching `VisitorProvider` to `VisitorFactory.PROVIDERS`
3. Add a case to `MigrateCommand.resolveDialects()`

Without step 2 or 3, the dialect will compile but the CLI cannot use it.

## Bundle design

`DialectBundle` is the feature map for a database.

Every bundle always includes:

- `BaseDialect`
- `DdlDialect`

Then it may also include:

- identity support
- sequence support
- table-generator support
- Liquibase support

Only add features the database actually supports.  
For example, the current MySQL bundle registers `identity`, `tableGenerator`, and `liquibase`, but not `sequence`.

## Visitors

SQL generation is selected through `VisitorFactory`.

Each database needs a `VisitorProvider` that:

- declares whether it supports the bundle database type
- creates table visitors
- creates table-content visitors
- optionally creates sequence visitors
- optionally creates table-generator visitors

This is why adding only a dialect class is not enough.

## CLI integration

The CLI currently resolves dialects with a `switch` in `MigrateCommand.resolveDialects()`.

That means a new dialect must also be added there, usually by:

- creating the dialect instance
- building a `DialectBundle`
- attaching only the supported optional dialect interfaces

If this step is missing, `jinx migrate --dialect <name>` will fail with `Unsupported dialect`.

## Recommended approach

Use `MySqlDialect` and `MySqlVisitorProvider` as the baseline.

Copy the structure first, make the new dialect generate basic `CREATE TABLE` and `ALTER TABLE` SQL, then add advanced support such as:

- sequences
- table generators
- Liquibase type mapping

That keeps the implementation small and easy to verify.
