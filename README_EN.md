# Jinx — JPA → DDL SQL / Liquibase Migration Generator

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx scans your JPA annotations to generate **schema snapshots (JSON)** and compares them to produce **DDL SQL** and **Liquibase migration YAML** automatically.

**MySQL first** | **JDK 21+ required** | **Latest version: 0.0.18** | **Supports JPA 3.2.0**

---

## Why Jinx?

* **Automatic schema analysis from JPA annotations**
  Track schema changes via JSON snapshots without writing DDL by hand.
* **Diff-based migration generation**
  Automatically detects renames, nullable changes, index additions, and more.
* **Outputs both DDL SQL and Liquibase YAML**
  Easily integrates with existing DB migration workflows.
* **Simple integration with Gradle & Java projects**

Sample entities and output
[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## Quick Start

### 1. Add dependencies

```gradle
dependencies {
    annotationProcessor("io.github.yyubin:jinx-processor:0.0.18")
    implementation("io.github.yyubin:jinx-core:0.0.18")
}
```

---

### 2. Write your entity

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

### 3. Generate schema snapshots

Snapshots are automatically created at

```
build/classes/java/main/jinx/
```

File naming rule

```
schema-<yyyyMMddHHmmss>.json
```

Example

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

### 4. Run migrations

If using the CLI directly

```bash
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

Example output (SQL)

```sql
CREATE TABLE `Bird` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255),
  `zoo_id` BIGINT,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE INDEX `ix_bird__zoo_id` ON `Bird` (`zoo_id`);
```

Example output (Liquibase YAML)

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

Below is a fully working setup for a typical Spring Boot project.

### 1) Add a dedicated configuration for jinx-cli

```gradle
val jinxCli by configurations.creating

dependencies {
    jinxCli("io.github.yyubin:jinx-cli:0.0.18")
}
```

### 2) Register the migration task

```gradle
tasks.register<JavaExec>("jinxMigrate") {
    group = "jinx"
    classpath = configurations["jinxCli"]
    mainClass.set("org.jinx.cli.JinxCli")

    // Ensure snapshots are generated first
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

### Running via Gradle

Generate SQL/YAML based on the latest snapshots

```bash
./gradlew jinxMigrate
```

Promote the latest snapshot to baseline

```bash
./gradlew jinxPromoteBaseline
```

---

## CLI Option Summary

| Option             | Description                                             |
| ------------------ | ------------------------------------------------------- |
| `migrate`          | Compare the latest two snapshots and create a migration |
| `promote-baseline` | Promote the current snapshot to baseline                |
| `-d, --dialect`    | DB dialect (e.g., `mysql`)                              |
| `--rollback`       | Generate rollback SQL                                   |
| `--liquibase`      | Output Liquibase YAML                                   |
| `--force`          | Allow potentially dangerous changes                     |

---

## Supported Features

* Table/column/PK/index/constraint creation & modification detection
* Automatic rename detection (in progress / improving)
* Rollback SQL generation
* Liquibase YAML generation
* MySQL dialect support
  (Additional dialects can be added via SPI)

---

## Example & Test Projects

More examples (entities, snapshots, SQL)

[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## Contributing

* Add new DB dialects
* Improve DDL/Liquibase mapping rules
* Expand test coverage & documentation

PRs and issues are welcome!
