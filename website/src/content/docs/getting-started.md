---
title: Getting Started
description: Install Jinx and generate your first migration.
---

## Requirements

- JDK 21 or later
- Gradle (Kotlin DSL recommended)
- JPA 3.2.0 (`jakarta.persistence`)

---

## Installation

### Option A: Gradle Plugin (recommended)

The Gradle plugin handles annotation processor configuration automatically.

```kotlin
plugins {
    id("io.github.yyubin.jinx") version "0.1.2"
}
```

Configure Jinx in your `build.gradle.kts`:

```kotlin
jinx {
    profile.set("local")

    naming {
        maxLength.set(63)
        strategy.set("SNAKE_CASE")
    }

    database {
        dialect.set("mysql")
    }

    output {
        format.set("sql")
        directory.set("build/jinx")
    }
}
```

### Option B: Annotation Processor directly

```gradle
dependencies {
    annotationProcessor("io.github.yyubin:jinx-processor:0.1.2")
    implementation("io.github.yyubin:jinx-core:0.1.2")
}
```

---

## Quick Start

### 1. Write a JPA entity

```java
@Entity
public class Bird {
    @Id @GeneratedValue
    private Long id;

    private String name;
    private Long zooId;
}
```

### 2. Compile

Jinx runs during compilation and writes a schema snapshot to:

```
build/classes/java/main/jinx/
```

Snapshot filename format:

```
schema-<yyyyMMddHHmmss>.json
```

Example snapshot:

```json
{
  "entities": {
    "org.example.Bird": {
      "tableName": "Bird",
      "columns": {
        "bird::id":     { "type": "BIGINT", "primaryKey": true, "autoIncrement": true },
        "bird::name":   { "type": "VARCHAR(255)" },
        "bird::zoo_id": { "type": "BIGINT" }
      },
      "indexes": {
        "ix_bird__zoo_id": { "columns": ["zoo_id"] }
      }
    }
  }
}
```

### 3. Generate migrations (CLI)

```bash
jinx db migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback
```

Example output:

```sql
CREATE TABLE `Bird` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255),
  `zoo_id` BIGINT,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE INDEX `ix_bird__zoo_id` ON `Bird` (`zoo_id`);
```

---

## CLI Reference

| Option             | Description                                       |
| ------------------ | ------------------------------------------------- |
| `db migrate`       | Generate SQL by diffing schema snapshots          |
| `promote-baseline` | Promote the current snapshot as the baseline      |
| `-p, --path`       | Path to directory containing snapshot JSON files  |
| `-d, --dialect`    | Database dialect (`mysql`)                        |
| `--out`            | Output directory for generated SQL files          |
| `--rollback`       | Also generate rollback SQL                        |
| `--liquibase`      | Output Liquibase YAML instead of SQL              |
| `--force`          | Allow potentially destructive changes             |

---

## Supported Features

- Table, column, primary key, index, and constraint diffing
- Rollback SQL generation
- Liquibase YAML output
- MySQL dialect included (additional dialects via SPI)
- `@ManyToOne`, `@OneToOne`, `@OneToMany`, `@ManyToMany` relationship handling
- `@Inheritance` strategies: `SINGLE_TABLE`, `JOINED`, `TABLE_PER_CLASS`
- `@ElementCollection` support
- `@EmbeddedId` / composite primary key support
- `@MapsId` support

---

## Examples

See the test repository for full entity examples and expected migration outputs:

[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)
