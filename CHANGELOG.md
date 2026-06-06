# Changelog

All notable changes to this project will be documented in this file.

---

## [0.1.3] - 2026-06-06

### Added

- **PostgreSQL dialect** — full DDL support via `-d postgresql` (CLI), `dialect.set("postgresql")` (Gradle plugin), or `PostgreSqlDialect` (programmatic)
  - Double-quoted identifiers
  - `BIGSERIAL` / `SERIAL` / `SMALLSERIAL` for auto-increment columns
  - Native `SEQUENCE` support (`@GeneratedValue(strategy = SEQUENCE)`)
  - Type mappings: `BOOLEAN`, `uuid`, `BYTEA`, `DOUBLE PRECISION`, `REAL`, `NUMERIC(p,s)`, `TIMESTAMP WITH TIME ZONE`, `TEXT` (unknown-type fallback)
  - Primary key constraint drop uses `{table}_pkey` naming convention
- **FK dependency-based table ordering** (`DependencyResolver`) — `CREATE TABLE` and `DROP TABLE` statements are now automatically ordered using a topological sort over foreign key relationships; no manual ordering required regardless of entity declaration order
  - Supported topologies: linear chains, diamond shapes, join tables, multiple independent trees
  - `noConstraint = true` relationships are excluded from the dependency graph
  - Circular FK detection: logs a warning to `stderr` and falls back to the original order safely
- **Renamed table handling** — tables detected as renamed are now generated as `DROP TABLE` + `CREATE TABLE` instead of `RENAME TABLE`, consistent with the intended production behaviour

### Fixed

- `toLowerCase()` in `DependencyResolver` now uses `Locale.ROOT` to prevent incorrect FK matching under locale-sensitive JVM environments (e.g. Turkish locale `I` → `ı`)
- BFS queue initialisation in `DependencyResolver` now uses `LinkedHashMap` insertion order for deterministic output when no FK dependency exists between independent tables

---

## [0.1.2] - 2026-03-09

### Added

- `naming.strategy` can now be configured via `jinx.yaml` (previously only supported through Gradle DSL or `-A` compiler options)

### Changed

- All annotation processor option parsing (`maxLength`, `strategy`) is now consolidated in `JpaSqlGeneratorProcessor.init()`. `ProcessingContext` no longer reads from `jinx.yaml` directly.

---

## [0.1.1] - 2026-03-08

### Fixed

- Fixed SQL generation order bug on column rename: indexes referencing the renamed column were generated before the column existed
- Resolved `ModifyContributor` dependency ordering issue in MySQL migrations by splitting modify operations into separate Drop and Add contributors

### Changed

- Index, constraint, and foreign key modify operations now follow explicit Drop → Column change → Add ordering

---

## [0.1.0] - 2025-03-07

### Fixed

- Fixed duplicate FK generation in JOINED inheritance hierarchies
- Fixed FK mismatch in multi-level JOINED inheritance
- Fixed `AttributeDescriptorFactory` incorrectly recursing into `@Entity` superclasses
- Fixed `TEXT` type being generated for `AttributeConverter` output types — now correctly resolves the converter's DB-side type (`Y` in `AttributeConverter<X, Y>`)
- Fixed deferred PK validation timing for JOINED hierarchies and `@ElementCollection`

---

## [0.0.22] - 2025-12-03

### Fixed

- Fixed constraint and index position bug in DDL generation
- Fixed duplicate column generation for boolean fields

### Changed

- Deprecated `IndexModifyContributor`, `ConstraintModifyContributor`, `RelationshipModifyContributor`

---

## [0.0.17]

### Added

- `NamingStrategy` SPI — `SNAKE_CASE` and `NO_OP` strategies included

---

## [0.0.14]

### Changed

- Deferred FK processing now uses dynamic retry count with progress detection to handle complex circular references

---

## [0.0.13]

### Added

- FK post-create phase separation for circular reference support

### Fixed

- `OneToOne` missing UNIQUE constraint on inverse side
- `ToOne` FK missing when processed before referenced entity

---

## [0.0.9]

### Fixed

- MySQL `ENUM` literal generation for `EnumType.STRING`
- Primitive and enum field SQL type mapping errors
