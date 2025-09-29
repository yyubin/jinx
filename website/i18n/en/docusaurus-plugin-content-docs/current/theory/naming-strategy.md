---
sidebar_position: 3
---

# Jinx Naming Strategy

Jinx's naming strategy prioritizes **consistency** to formalize and systematically manage naming conventions for database objects. This document explains the implemented rules and the collision‑prevention mechanisms in detail.

## Table of Contents

1. [Overview](#overview)
2. [Base Naming Rules](#base-naming-rules)
3. [Normalization Rules](#normalization-rules)
4. [Length Limits & Hash Truncation](#length-limits--hash-truncation)
5. [ColumnKey‑Based Collision Prevention](#columnkey-based-collision-prevention)
6. [Configuration & Customization](#configuration--customization)

## Overview

### Core Principles

1. **Consistency**: All DB objects follow the same rules.
2. **Predictability**: Object names are predictable if you know the rules.
3. **Collision Prevention**: Prevent generation of identical names.
4. **Length Limits**: Respect DB‑specific identifier length limits.
5. **Readability**: Names should be developer‑friendly.

### Architecture

```java
public interface Naming {
    String joinTableName(String leftTable, String rightTable);
    String foreignKeyColumnName(String ownerName, String referencedPkColumnName);
    String pkName(String tableName, List<String> columns);
    String fkName(String fromTable, List<String> fromColumns, String toTable, List<String> toColumns);
    String uqName(String tableName, List<String> columns);
    String ixName(String tableName, List<String> columns);
    // ... other constraint naming methods
}
```

**Default Implementation**: `DefaultNaming`

* Configurable maximum length
* Hash‑based truncation
* Normalization & collision avoidance

## Base Naming Rules

### 1. Foreign Key (Constraint) Name

**Pattern**: `fk_<childTable>__<childCols>__<parentTable>`

```java
// DefaultNaming.fkName()
public String fkName(String childTable, List<String> childCols, String parentTable, List<String> parentCols) {
    String base = "fk_"
            + norm(childTable)
            + "__"
            + joinNormalizedColumns(childCols)  // sorted columns
            + "__"
            + norm(parentTable);
    return clampWithHash(base);
}
```

**Examples:**

```sql
-- @ManyToOne User user;
-- Column user_id in Order references id in User
fk_order__user_id__user

-- Composite FK (columns sorted alphabetically)
-- order_id, product_id in OrderItem
fk_orderitem__order_id_product_id__order
```

### 2. Index Name

**Pattern**: `ix_<table>__<cols>`

```java
public String ixName(String table, List<String> cols) {
    return buildNameWithColumns("ix_", table, cols);
}

private String buildNameWithColumns(String prefix, String table, List<String> cols) {
    String base = prefix + norm(table) + "__" + joinNormalizedColumns(cols);
    return clampWithHash(base);
}
```

**Examples:**

```sql
ix_user__email                    -- single column
ix_order__customer_id_order_date  -- composite (alpha‑sorted)
```

### 3. Join Table Name (@ManyToMany)

**Pattern**: `jt_<A>__<B>` (entity/table names combined in alphabetical order)

```java
public String joinTableName(String leftTable, String rightTable) {
    String a = norm(leftTable);
    String b = norm(rightTable);
    String base = (a.compareTo(b) <= 0) ? a + "__" + b : b + "__" + a;
    return clampWithHash("jt_" + base);
}
```

**Example:**

```sql
-- @ManyToMany List<Role> roles; (User ↔ Role)
jt_role__user
-- Join table columns
role_id, user_id
```

### 4. Collection Table Name (@ElementCollection)

**Pattern**: `<owningTable>_<attrName>`

```java
// Implemented in ElementCollectionHandler
// Typical pattern: ownerEntity.getTableName() + "_" + attributeName
```

**Examples:**

```sql
user_tags
user_addresses
```

### 5. Foreign Key Column Name

**Pattern**: `<ownerName>_<referencedPkColumnName>`

```java
public String foreignKeyColumnName(String ownerName, String referencedPkColumnName) {
    return norm(ownerName) + "_" + norm(referencedPkColumnName);
}
```

**Usage Patterns:**

* **FK in normal entity tables**: `fieldName + referencedPK`
* **FK in join tables**: `entityTableName + referencedPK`

**Examples:**

```sql
user_id
customer_customer_id  -- when Customer PK is customer_id
-- In join tables:
user_id, role_id
```

### 6. Other Constraints

```java
// Primary Key
public String pkName(String table, List<String> cols) {
    return buildNameWithColumns("pk_", table, cols);
}

// Unique
public String uqName(String table, List<String> cols) {
    return buildNameWithColumns("uq_", table, cols);
}

// Check
public String ckName(String tableName, List<String> columns) {
    return buildNameWithColumns("ck_", tableName, columns);
}

// Not Null (if named)
public String nnName(String tableName, List<String> columns) {
    return buildNameWithColumns("nn_", tableName, columns);
}
```

**Examples:**

```sql
pk_user__id
pk_orderitem__order_id_product_id
uq_user__email
ck_user__age_status
```

## Normalization Rules

All table and column names are standardized by the following normalization:

### Normalization Algorithm

```java
private String norm(String s) {
    if (s == null) return "null";
    String x = s.replaceAll("[^A-Za-z0-9_]", "_");       // disallowed chars → '_'
    x = x.replaceAll("_+", "_");                          // collapse multiple '_'
    x = x.toLowerCase();                                    // lowercase
    if (x.isEmpty() || x.chars().allMatch(ch -> ch == '_')) {
        return "x";                                         // empty or only '_'
    }
    return x;
}
```

### Normalization Examples

| Input            | Output         | Note                             |
| ---------------- | -------------- | -------------------------------- |
| `"UserTable"`    | `"usertable"`  | lowercased                       |
| `"User-Table"`   | `"user_table"` | special chars → `_`              |
| `"User  Table!"` | `"user_table"` | spaces/punct → `_`, collapse `_` |
| `"___"`          | `"x"`          | meaningless → `x`                |
| `""`             | `"x"`          | empty → `x`                      |
| `null`           | `"null"`       | explicit null sentinel           |

### Column Ordering Rule

Constraint names involving multiple columns use **alphabetical ordering**:

```java
private String joinNormalizedColumns(List<String> cols) {
    if (cols == null || cols.isEmpty()) return "";
    List<String> normalized = cols.stream()
            .map(this::norm)
            .collect(Collectors.toCollection(ArrayList::new));
    Collections.sort(normalized, String.CASE_INSENSITIVE_ORDER);
    return String.join("_", normalized);
}
```

**Benefits:**

* Identical names regardless of input order
* Prevents duplicates
* Predictable naming

## Length Limits & Hash Truncation

### Configuring the Limit

```java
// Loaded from ProcessingContext
private int parseMaxLength(Map<String, String> config) {
    String maxLenOpt = config.get(JinxOptions.Naming.MAX_LENGTH_KEY);
    int maxLength = JinxOptions.Naming.MAX_LENGTH_DEFAULT; // typically 63
    if (maxLenOpt != null) {
        try {
            maxLength = Integer.parseInt(maxLenOpt);
        } catch (NumberFormatException e) {
            // warn and keep default
        }
    }
    return maxLength;
}
```

**Ways to set:**

* **Compiler option**: `-Ajinx.naming.maxLength=63`
* **Config file**: `jinx.properties` → `jinx.naming.maxLength=63`
* **Env var**: `JINX_NAMING_MAX_LENGTH=63`

### Hash Truncation Mechanism

If the name exceeds the limit, it is truncated and a hash is appended:

```java
private String clampWithHash(String name) {
    if (name.length() <= maxLength) return name;
    String hash = Integer.toHexString(name.hashCode());
    int keep = Math.max(1, maxLength - (hash.length() + 1)); // account for '_'
    return name.substring(0, keep) + "_" + hash;
}
```

**Truncation Examples (maxLength=20):**

| Original Name                             | Truncated              | Note                |
| ----------------------------------------- | ---------------------- | ------------------- |
| `fk_very_long_table_name__column__target` | `fk_very_lon_1a2b3c4d` | prefix + '_' + hash |
| `ix_user__email`                          | `ix_user__email`       | within limit        |

**Benefits:**

* **Uniqueness**: hash collisions are extremely unlikely
* **Deterministic**: same input → same output
* **Space‑efficient**: respects max length
* **Traceable**: hash allows backtracking to source

## ColumnKey‑Based Collision Prevention

### Concept

`ColumnKey` uniquely identifies a table/column pair:

```java
public final class ColumnKey implements Comparable<ColumnKey> {
    private final String canonical;  // normalized (for DB compare)
    private final String display;    // original (for display)

    public static ColumnKey of(String tableName, String columnName) {
        String displayKey = tableName + "::" + columnName;
        String canonicalKey = tableName.toLowerCase() + "::" + columnName.toLowerCase();
        return new ColumnKey(canonicalKey, displayKey);
    }

    public static ColumnKey of(String tableName, String columnName, CaseNormalizer normalizer) {
        String displayKey = tableName + "::" + columnName;
        String canonicalKey = normalizer.normalize(tableName) + "::" + normalizer.normalize(columnName);
        return new ColumnKey(canonicalKey, displayKey);
    }
}
```

### Format

**Pattern**: `<tableName>::<columnName>`

**Examples:**

```java
ColumnKey.of("User", "email")        // display: "User::email"; canonical: "user::email"
ColumnKey.of("OrderItem", "productId") // display: "OrderItem::productId"; canonical: "orderitem::productid"
```

### Case Normalization Policies

Support different DB case policies:

```java
public interface CaseNormalizer {
    String normalize(String identifier);
}

public class LowerCaseNormalizer implements CaseNormalizer {
    public String normalize(String identifier) {
        return identifier == null ? "" : identifier.toLowerCase(Locale.ROOT);
    }
}

public class UpperCaseNormalizer implements CaseNormalizer {
    public String normalize(String identifier) {
        return identifier == null ? "" : identifier.toUpperCase(Locale.ROOT);
    }
}
```

### Collision Prevention Mechanics

1. **Normalized comparison** via `canonical`
2. **Original preserved** via `display`
3. **Stable ordering** via `Comparable`
4. **Map key‑ready** with proper `equals()`/`hashCode()`

```java
public List<ColumnKey> getColumnsKeys(CaseNormalizer normalizer) {
    return columns.stream()
        .map(col -> ColumnKey.of(tableName, col, normalizer))
        .toList();
}
```

## Configuration & Customization

### Default Config

```properties
# jinx.properties
jinx.naming.maxLength=63
```

### Compile‑Time Config

```bash
# Maven
mvn compile -Djinx.naming.maxLength=128

# Gradle
gradle compileJava -Ajinx.naming.maxLength=128
```

### Programmatic Config

```java
public ProcessingContext(ProcessingEnvironment processingEnv, SchemaModel schemaModel) {
    Map<String, String> config = loadConfiguration(processingEnv);
    int maxLength = parseMaxLength(config);
    this.naming = new DefaultNaming(maxLength);
}
```

### Custom Naming Implementation

```java
public class CustomNaming implements Naming {
    private final int maxLength;

    public CustomNaming(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public String fkName(String fromTable, List<String> fromColumns, String toTable, List<String> toColumns) {
        return "custom_fk_" + fromTable + "_to_" + toTable;
    }

    // customize other methods as needed...
}
```

## Summary Table of Rules

| Object Type        | Prefix | Pattern                        | Example                   |
| ------------------ | ------ | ------------------------------ | ------------------------- |
| **Foreign Key**    | `fk_`  | `fk_<child>__<cols>__<parent>` | `fk_order__user_id__user` |
| **Index**          | `ix_`  | `ix_<table>__<cols>`           | `ix_user__email`          |
| **Join Table**     | `jt_`  | `jt_<A>__<B>` (alphabetical)   | `jt_role__user`           |
| **Collection Tbl** | –      | `<owner>_<attr>`               | `user_addresses`          |
| **Primary Key**    | `pk_`  | `pk_<table>__<cols>`           | `pk_user__id`             |
| **Unique**         | `uq_`  | `uq_<table>__<cols>`           | `uq_user__email`          |
| **Check**          | `ck_`  | `ck_<table>__<cols>`           | `ck_user__age`            |
| **FK Column**      | –      | `<owner>_<refPK>`              | `user_id`                 |

### Normalization & Collision‑Prevention Highlights

1. **Uniform normalization**: lowercase + special‑char removal
2. **Column ordering**: alpha‑sorted for multi‑column artifacts
3. **Length enforcement**: configurable limit + hash truncation
4. **ColumnKey**: unique `table::column` identifier
5. **Collision safety**: hash‑based uniqueness