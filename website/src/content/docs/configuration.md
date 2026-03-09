---
title: Configuration
description: Full reference for Jinx configuration options.
---

Jinx supports three configuration methods. Priority order (higher overrides lower):

```
Gradle DSL / -A compiler options  >  jinx.yaml  >  defaults
```

---

## jinx.yaml

Create `jinx.yaml` in your project root. Jinx searches upward from the working directory,
so a file at the repository root covers all subprojects.

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

## Configuration Options

### `naming.maxLength`

Maximum length for generated constraint and index names.

| Default | Recommended values                       |
|---------|------------------------------------------|
| `30`    | PostgreSQL: `63` &nbsp;/&nbsp; MySQL: `64` |

When a generated name exceeds this limit, Jinx truncates it and appends a short hash to preserve uniqueness.

### `naming.strategy`

Controls how Java field/class names are converted to physical column names.

| Value        | Behavior                        | Example                      |
|--------------|---------------------------------|------------------------------|
| `NO_OP`      | No conversion (default)         | `myColumn` → `myColumn`      |
| `SNAKE_CASE` | camelCase → snake_case          | `myColumn` → `my_column`     |

---

## Gradle DSL

```kotlin
jinx {
    profile.set("prod")

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

Gradle DSL values override `jinx.yaml`. The plugin translates them into `-A` compiler arguments automatically.

---

## Direct Annotation Processor Options (`-A`)

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

| Key                      | Description                              |
|--------------------------|------------------------------------------|
| `jinx.naming.maxLength`  | Maximum constraint/index name length     |
| `jinx.naming.strategy`   | Naming strategy (`NO_OP` or `SNAKE_CASE`)|
| `jinx.profile`           | Active profile for `jinx.yaml` lookup    |

---

## Support Matrix

| Setting               | jinx.yaml | Gradle DSL | `-A` option |
|-----------------------|-----------|------------|-------------|
| `profile`             | ✓         | ✓          | ✓           |
| `naming.maxLength`    | ✓         | ✓          | ✓           |
| `naming.strategy`     | ✓         | ✓          | ✓           |
| `database.dialect`    | —         | ✓          | —           |
| `output.format`       | —         | ✓          | —           |
| `output.directory`    | —         | ✓          | —           |
