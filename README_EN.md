# Jinx — JPA → DDL / Liquibase Migration Generator

> 📖 **한국어로 읽기**: [README.md](./README.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx is a tool that scans JPA annotations to create **schema snapshots (JSON)** and automatically generates **DB migration SQL** and **Liquibase YAML** by comparing with previous snapshots.

**MySQL priority support** | **JDK 21+ required** | **Latest release: 0.0.17** | **JPA 3.2.0+ required**

## Why Jinx?

- **JPA annotations → snapshots → diff-based auto generation**: No manual DDL writing required
- **DDL + Liquibase YAML simultaneous output**: Compatible with existing migration tools
- **Phase-based incremental changes**: Support for safe migrations like rename/backfill (roadmap)

Sample entities/JSON/SQL can be found in the [jinx-test repository](https://github.com/yyubin/jinx-test).

---

## Quick Start

### 1. Add Dependencies

```gradle
dependencies {
    annotationProcessor "io.github.yyubin:jinx-processor:0.0.17"
    implementation "io.github.yyubin:jinx-core:0.0.17"
}
```

---

### 2. Write Entities

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

### 3. Generate Snapshots

When you build, schema snapshots are generated in the `build/classes/java/main/jinx/` path.

**Filename convention**: `schema-<yyyyMMddHHmmss>.json` (KST timezone, collision prevention)

**Automatic index inference**: `Long zooId` → `zoo_id` column → `ix_<table>__<column>` index creation

Example: `schema-20250922010911.json`

```json
{
  "entities": {
    "org.example.Bird": {
      "tableName": "Bird",
      "columns": {
        "bird::id": { "type": "BIGINT", "primaryKey": true, "autoIncrement": true },
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

### 4. Run Migration

Compares the latest 2 snapshots to generate SQL and Liquibase YAML.

```bash
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

Generated SQL example:

```sql
CREATE TABLE `Bird` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255),
  `zoo_id` BIGINT,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX `ix_bird__zoo_id` ON `Bird` (`zoo_id`);

```

Liquibase YAML is also generated:

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
              - column:
                  name: name
                  type: VARCHAR(255)
              - column:
                  name: zoo_id
                  type: BIGINT

```

---

## Example Project

Check the [jinx-test](https://github.com/yyubin/jinx-test) repository for more entity, snapshot, and migration SQL samples.

---

## CLI Options

| Option | Description | Default |
| --- | --- | --- |
| `-p, --path` | Schema JSON directory | `build/classes/java/main/jinx` |
| `-d, --dialect` | DB dialect (e.g., `mysql`) | - |
| `--out` | Output path | `build/jinx` |
| `--rollback` | Generate rollback SQL | Disabled |
| `--liquibase` | Generate Liquibase YAML | Disabled |
| `--force` | Force allow dangerous changes | Disabled |

---

---

## Advanced: Gradle Integration

By adding a **dedicated configuration + JavaExec task**, you can run everything from build artifact (schema snapshot) generation → CLI execution in one go.

**`build.gradle` example (Gradle 8+, JDK 21)**

```
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.5'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'org.jinx'
version = '0.0.1-SNAPSHOT'
description = 'jinx-test'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom annotationProcessor
    }
    jinxCli
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    compileOnly 'org.projectlombok:lombok'
    runtimeOnly 'com.mysql:mysql-connector-j'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

    // Jinx (0.0.17)
    implementation       "io.github.yyubin:jinx-core:0.0.17"
    annotationProcessor  "io.github.yyubin:jinx-processor:0.0.17"

    // CLI (includes transitives)
    jinxCli              "io.github.yyubin:jinx-cli:0.0.17"
}

// Default values that can be overridden with gradle -P properties
ext.defaultJinxPath      = "build/classes/java/main/jinx"
ext.defaultJinxDialect   = "mysql"
ext.defaultJinxOut       = "build/jinx"

tasks.register('jinxMigrate', JavaExec) {
    group = 'jinx'
    description = 'Run Jinx CLI to generate migration SQL / Liquibase YAML'
    classpath = configurations.jinxCli
    mainClass = 'org.jinx.cli.JinxCli'

    // Can be overridden with project properties
    def p  = (String) (project.findProperty("jinxPath")    ?: defaultJinxPath)
    def d  = (String) (project.findProperty("jinxDialect") ?: defaultJinxDialect)
    def out= (String) (project.findProperty("jinxOut")     ?: defaultJinxOut)

    // Flag options: check for existence only
    def withLb  = project.hasProperty("jinxLiquibase")
    def withRb  = project.hasProperty("jinxRollback")
    def force   = project.hasProperty("jinxForce")

    // CLI subcommand: migrate
    args 'migrate',
         '-p', p,
         '-d', d,
         '--out', out

    if (withLb) args '--liquibase'
    if (withRb) args '--rollback'
    if (force)  args '--force'

    // Ensure snapshots are generated first
    dependsOn 'classes'
}

tasks.named('test') {
    useJUnitPlatform()
}

```

### Execution Examples

Run with default values (path/dialect/output path)

```bash
./gradlew jinxMigrate

```

Run with changed parameters (override with project properties)

```bash
./gradlew jinxMigrate \
  -PjinxPath=build/classes/java/main/jinx \
  -PjinxDialect=mysql \
  -PjinxOut=build/jinx \
  -PjinxLiquibase \
  -PjinxRollback

```

Windows PowerShell:

```powershell
.\gradlew.bat jinxMigrate -PjinxDialect=mysql -PjinxLiquibase

```

> Note: Gradle's --args is an option for the default run task. Using project properties (-P) as shown above is the cleanest and most portable approach for custom JavaExec tasks.
>

### Checklist (Common Issues)

- **If there's only one snapshot**, "compare latest 2" is not possible. Run a new build once more to secure 2 or more snapshots.
- If **Annotation Processing is disabled** in your IDE, snapshots won't be generated.
- Dialect (`d`) currently prioritizes `mysql` support. Other DBs can be extended by implementing the SPI interface (`org.jinx.dialect.Dialect`).

### More Examples

Check actual entities/snapshots/SQL outputs here:

**jinx-test** → https://github.com/yyubin/jinx-test

---

## Currently Supported Features

- Major DDL including tables/columns/PK/indexes/constraints/rename
- ID strategies: `IDENTITY`, `SEQUENCE`, `TABLE`
- Liquibase YAML output
- MySQL Dialect provided by default (other DBs can be added via SPI interface extension)

---

## License
Jinx is licensed under the [Apache License 2.0](LICENSE).

---

## Contributing

- Add new DB dialects
- Validate DDL ↔ Liquibase mappings
- Expand test cases
- Improve documentation

PRs and issues are welcome.