# Jinx — JPA → DDL SQL Migration Generator

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yyubin.jinx)](https://plugins.gradle.org/plugin/io.github.yyubin.jinx)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx analyzes your **JPA annotations at compile time**, generates **schema snapshots (JSON)**, and produces **DDL SQL** by diffing schema changes over time.

Liquibase YAML is supported as an **output dialect**, but **SQL is the primary and most thoroughly validated output format**.

**MySQL First** | **JDK 21+ Required** | **Latest Version: 0.1.1** | **JPA 3.2.0 Supported**

---

## Why Jinx?

Jinx exists to make database schema evolution **explicit, reviewable, and automation‑friendly**.

### 1. Prevent human error in DDL

DDL is generated from JPA metadata instead of being handwritten.
Typos, missing columns, and inconsistent constraints are eliminated before they reach production.

### 2. Developer‑reviewable migrations

Jinx outputs plain SQL files.
Schema changes can be reviewed, discussed, and approved just like application code.

### 3. CI/CD‑friendly by default

Because Jinx produces SQL files, migrations integrate naturally into existing CI/CD pipelines
without requiring a live database connection.

### 4. No database required

Schema analysis and diffing operate purely on snapshot files.
You can generate and validate migrations without connecting to an actual database.

### 5. Migration history without extra runtime tooling

Generated SQL files can be committed to Git.
If you do not want to introduce a dedicated migration runtime,
Git itself becomes your schema history and audit trail.

---

## Design Philosophy

### Compile‑time analysis (no reflection)

Jinx performs schema analysis using **annotation processing** at compile time.
It does **not rely on runtime reflection** and does **not strictly follow the reflection‑based JPA specification model**.

This design is intentional:

* Deterministic schema generation
* Zero runtime metadata requirements
* Compatibility with AOT‑oriented build pipelines

As the Java ecosystem continues to reduce reflection usage,
Jinx remains naturally aligned with static and reproducible builds.

> Jinx does **not** replace JPA runtimes such as Hibernate.
> It focuses exclusively on schema analysis and migration generation.

---

## Output Formats

### SQL (Primary)

DDL SQL is Jinx’s first‑class output and receives the most validation.

### Liquibase YAML (Secondary)

Liquibase output is provided as a compatible dialect for teams that already rely on Liquibase
for execution and tracking.

Liquibase support is **not the core model**, but a translation layer on top of SQL generation.

---

## Quick Start

### 1. Add dependencies

```gradle
dependencies {
    annotationProcessor("io.github.yyubin:jinx-processor:0.1.1")
    implementation("io.github.yyubin:jinx-core:0.1.1")
}
```

---

### 2. Create an entity

```java
@Entity
public class Bird {
    @Id @GeneratedValue
    private Long id;

    private String name;
    private Long zooId;
}
```

---

### 3. Generate snapshots

Snapshots are generated automatically during compilation:

```
build/classes/java/main/jinx/
```

Snapshot naming format:

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
        "bird::id":   { "type": "BIGINT", "primaryKey": true, "autoIncrement": true },
        "bird::name": { "type": "VARCHAR(255)" },
        "bird::zoo_id": { "type": "BIGINT" }
      },
      "indexes": {
        "ix_bird__zoo_id": { "columns": ["zoo_id"] }
      }
    }
  }
}
```

---

### 4. Run migrations (CLI)

```bash
jinx db migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

Example SQL output:

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

## Gradle Integration (Spring Boot & JDK 21)

### Apply the Gradle plugin

```kotlin
plugins {
    id("io.github.yyubin.jinx") version "0.1.1"
}
```

---

### Example DSL configuration

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

---

## Configuration

### Priority Order

Settings are applied in this order (higher overrides lower):

```
Gradle DSL / -A compiler options  >  jinx.yaml  >  defaults
```

---

### jinx.yaml

Create `jinx.yaml` in your project root. Jinx searches upward from the working directory, so a file at the repository root covers all subprojects.

```yaml
profiles:
  dev:
    naming:
      maxLength: 30
      strategy: NO_OP

  prod:
    naming:
      maxLength: 63
      strategy: SNAKE_CASE
```

**Activate a profile** (in order of precedence):
1. Gradle DSL: `profile.set("prod")`
2. Environment variable: `JINX_PROFILE=prod`
3. Default: `dev`

---

### Configuration Options

#### `naming.maxLength`

Maximum length for generated constraint and index names.

| Default | Recommended values |
|---------|--------------------|
| `30`    | PostgreSQL: `63` / MySQL: `64` |

#### `naming.strategy`

Controls how logical names (Java field/class names) are converted to physical column names.

| Value | Behavior | Example |
|-------|----------|---------|
| `NO_OP` | No conversion (default) | `myColumn` → `myColumn` |
| `SNAKE_CASE` | camelCase → snake_case | `myColumn` → `my_column` |

---

### Gradle DSL

```kotlin
jinx {
    profile.set("prod")

    naming {
        maxLength.set(63)
        strategy.set("SNAKE_CASE")
    }
}
```

Gradle DSL values override `jinx.yaml`. The plugin translates them into `-A` compiler arguments automatically.

---

### Direct Annotation Processor Options (`-A`)

When using the annotation processor without the Gradle plugin:

```gradle
compileJava {
    options.compilerArgs += [
        '-Ajinx.naming.maxLength=63',
        '-Ajinx.naming.strategy=SNAKE_CASE',
        '-Ajinx.profile=prod'
    ]
}
```

| Key | Description |
|-----|-------------|
| `jinx.naming.maxLength` | Maximum constraint/index name length |
| `jinx.naming.strategy`  | Naming strategy (`NO_OP` or `SNAKE_CASE`) |
| `jinx.profile`          | Active profile for `jinx.yaml` lookup |

---

## CLI Option Summary

| Option             | Description                                  |
| ------------------ | -------------------------------------------- |
| `db migrate`       | Generate SQL by diffing schema snapshots     |
| `promote-baseline` | Promote the current snapshot as the baseline |
| `-d, --dialect`    | Database dialect (mysql, etc.)               |
| `--rollback`       | Generate rollback SQL                        |
| `--liquibase`      | Output Liquibase YAML                        |
| `--force`          | Allow potentially destructive changes        |

---

## Supported Features

* Table, column, primary key, index, and constraint diffing
* Rollback SQL generation
* Liquibase YAML output
* MySQL dialect included (additional dialects via SPI)

---

## Examples & Test Repository

[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## Contributing

* New database dialects
* Improved DDL or Liquibase mappings
* Tests and documentation

Pull requests and issues are welcome.
