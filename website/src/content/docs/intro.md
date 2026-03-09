---
title: Introduction
description: What is Jinx and why does it exist?
---

Jinx analyzes your **JPA annotations at compile time**, generates **schema snapshots (JSON)**, and produces **DDL SQL** by diffing snapshots over time.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yyubin.jinx)](https://plugins.gradle.org/plugin/io.github.yyubin.jinx)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/yyubin/jinx/blob/main/LICENSE)

**MySQL First** · **JDK 21+ Required** · **Latest Version: 0.1.2** · **JPA 3.2.0 Supported**

Liquibase YAML is supported as an output dialect, but **SQL is the primary and most thoroughly validated output format**.

---

## Why Jinx?

Jinx exists to make database schema evolution **explicit, reviewable, and automation-friendly**.

### Prevent human error in DDL

DDL is generated from JPA metadata instead of being handwritten.
Typos, missing columns, and inconsistent constraints are eliminated before they reach production.

### Developer-reviewable migrations

Jinx outputs plain SQL files.
Schema changes can be reviewed, discussed, and approved just like application code.

### CI/CD-friendly by default

Because Jinx produces SQL files, migrations integrate naturally into existing CI/CD pipelines
without requiring a live database connection.

### No database required

Schema analysis and diffing operate purely on snapshot files.
You can generate and validate migrations without connecting to an actual database.

### Migration history without extra runtime tooling

Generated SQL files can be committed to Git.
If you do not want to introduce a dedicated migration runtime,
Git itself becomes your schema history and audit trail.

---

## Design Philosophy

### Compile-time analysis (no reflection)

Jinx performs schema analysis using **annotation processing** at compile time.
It does **not rely on runtime reflection** and does **not strictly follow the reflection-based JPA specification model**.

This design is intentional:

- Deterministic schema generation
- Zero runtime metadata requirements
- Compatibility with AOT-oriented build pipelines

As the Java ecosystem continues to reduce reflection usage,
Jinx remains naturally aligned with static and reproducible builds.

:::note
Jinx does **not** replace JPA runtimes such as Hibernate.
It focuses exclusively on schema analysis and migration generation.
:::

---

## Output Formats

### SQL (Primary)

DDL SQL is Jinx's first-class output and receives the most validation.

### Liquibase YAML (Secondary)

Liquibase output is provided as a compatible dialect for teams that already rely on Liquibase
for execution and tracking. It is not the core model, but a translation layer on top of SQL generation.
