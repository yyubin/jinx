---
sidebar_position: 1
---

# Getting Started with Jinx

**Jinx** is a tool that scans JPA annotations to create schema snapshots (JSON) and automatically generates **DB migration SQL** and **Liquibase YAML** by comparing with previous snapshots.

**Currently prioritizes MySQL support** | **Requires JDK 21+** | **Latest release: 0.0.10**

## Quick Start

### 1. Add Dependencies

```gradle
dependencies {
    annotationProcessor "io.github.yyubin:jinx-processor:0.0.10"
    implementation "io.github.yyubin:jinx-core:0.0.10"
}
```

### 2. Create Entity

```java
@Entity
public class Bird {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private Long zooId;
}
```

### 3. Generate Snapshot

After building, schema snapshots are generated in `build/classes/java/main/jinx/` directory.

**File naming convention**: `schema-<yyyyMMddHHmmss>.json` (KST timezone, prevents conflicts)

### 4. Run Migration

Generates SQL and Liquibase YAML by comparing the latest 2 snapshots.

```bash
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

## More Information

For more examples and configuration methods, please refer to the [GitHub Repository](https://github.com/yyubin/jinx).
