# Jinx — JPA → DDL SQL / Liquibase Migration Generator

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx scans your JPA annotations to generate **schema snapshots (JSON)** and automatically produces **DDL SQL** and **Liquibase migration YAML** by comparing changes across snapshots.

**MySQL First** | **JDK 21+ Required** | **Latest Version: 0.0.18** | **JPA 3.2.0 Supported**

---

## Why Jinx?

* **Automatic schema analysis based on JPA annotations**  
  No manual DDL writing—schema snapshots track changes over time.
* **Diff-based migration generation**  
  Automatically detects renames, nullable changes, index additions, and more.
* **Outputs both DDL SQL and Liquibase YAML**  
  Works seamlessly with existing migration tools.
* **Easy integration with Gradle and Java projects**

Sample entities and outputs:  
https://github.com/yyubin/jinx-test

---

## Quick Start

### 1. Add dependencies

```gradle
dependencies {
    annotationProcessor("io.github.yyubin:jinx-processor:0.0.18")
    implementation("io.github.yyubin:jinx-core:0.0.18")
}
````

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

Snapshots are automatically generated when you build:

```
build/classes/java/main/jinx/
```

Snapshot file naming:

```
schema-<yyyyMMddHHmmss>.json
```

Example:

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
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

Example output (SQL):

```sql
CREATE TABLE `Bird` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255),
  `zoo_id` BIGINT,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE INDEX `ix_bird__zoo_id` ON `Bird` (`zoo_id`);
```

Example (Liquibase YAML):

```yaml
databaseChangeLog:
  - changeSet:
      id: 20250922010911-1
      author: jinx
      changes:
        - createTable:
            tableName: Bird
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
```

---

## Gradle Integration (Spring Boot & JDK 21)

Below is a minimal example of integrating Jinx into a Spring Boot project.

### 1) Create a dedicated configuration for jinx-cli

```gradle
val jinxCli by configurations.creating

dependencies {
    jinxCli("io.github.yyubin:jinx-cli:0.0.18")
}
```

### 2) Register a migration task

```gradle
tasks.register<JavaExec>("jinxMigrate") {
    group = "jinx"
    classpath = configurations["jinxCli"]
    mainClass.set("org.jinx.cli.JinxCli")

    dependsOn("classes")
    args("db", "migrate", "-d", "mysql")
}
```

### 3) Register a baseline promotion task

```gradle
tasks.register<JavaExec>("jinxPromoteBaseline") {
    group = "jinx"
    classpath = configurations["jinxCli"]
    mainClass.set("org.jinx.cli.JinxCli")

    dependsOn("classes")
    args("db", "promote-baseline", "--force")
}
```

---

### Run via Gradle

Generate SQL/YAML from the latest snapshots:

```bash
./gradlew jinxMigrate
```

Promote the latest snapshot as the new baseline:

```bash
./gradlew jinxPromoteBaseline
```

---

## Gradle Plugin (Pending Publication)

The official **Jinx Gradle Plugin is currently under review** in the Gradle Plugin Portal.
Once approved, you can apply it as:

```kotlin
plugins {
    id("org.jinx.gradle") version "0.0.18"
}
```

Until then, you may apply it manually:

```kotlin
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.jinx:jinx-gradle:0.0.18")
    }
}

apply(plugin = "org.jinx.gradle")
```

---

## DSL Example

```kotlin
jinx {
    profile.set("local")

    naming {
        maxLength.set(63)
        strategy.set("SNAKE_CASE")
    }

    database {
        dialect.set("mysql")
        url.set("jdbc:mysql://localhost:3306/app")
    }

    output {
        format.set("liquibase")
        directory.set("build/jinx")
    }
}
```

---

## Plugin Publication Status

Once the plugin is approved, it will be available at:

```
https://plugins.gradle.org/plugin/org.jinx.gradle
```

---

## CLI Option Summary

| Option             | Description                                       |
| ------------------ | ------------------------------------------------- |
| `migrate`          | Compare the latest two snapshots and generate SQL |
| `promote-baseline` | Promote the current snapshot as the new baseline  |
| `-d, --dialect`    | Database dialect (mysql, etc.)                    |
| `--rollback`       | Generate rollback SQL                             |
| `--liquibase`      | Output Liquibase YAML                             |
| `--force`          | Allow dangerous schema changes                    |

---

## Supported Features

* Table/column/PK/index/constraint creation & update detection
* Automatic rename detection (progressively improving)
* Rollback SQL generation
* Liquibase YAML generation
* MySQL dialect included
  (Additional dialects can be added via SPI)

---

## Examples & Test Repository

[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## Contributing

* Add new DB dialects
* Improve DDL/Liquibase mapping
* Provide tests & documentation

PRs and issues are welcome!
