---
sidebar_position: 1
---

# Adding New Database Dialect

> 📖 **한국어로 읽기**: [새로운 데이터베이스 방언 추가하기](./새로운-데이터베이스-방언-추가하기.md)

This guide provides step-by-step instructions for adding new database dialect support to Jinx. Jinx uses an extensible SPI (Service Provider Interface) based architecture to support various databases.

## Table of Contents

1. [Dialect Architecture Overview](#dialect-architecture-overview)
2. [Step-by-Step Implementation Guide](#step-by-step-implementation-guide)
3. [SPI Interface Implementation](#spi-interface-implementation)
4. [Dialect Bundle Configuration](#dialect-bundle-configuration)
5. [VisitorFactory Registration](#visitorfactory-registration)
6. [CLI Integration](#cli-integration)
7. [Writing Tests](#writing-tests)
8. [Real Example: Adding PostgreSQL Dialect](#real-example-adding-postgresql-dialect)

## Dialect Architecture Overview

### Core Components

Jinx's dialect system consists of the following layers:

```
DialectBundle
├── BaseDialect (Basic identifier handling)
├── DdlDialect (DDL generation)
├── IdentityDialect (AUTO_INCREMENT etc.)
├── SequenceDialect (Sequence support)
├── TableGeneratorDialect (Table-based ID generation)
└── LiquibaseDialect (Liquibase output)
```

### SPI Interfaces

```java
// Basic policies
public interface IdentifierPolicy {
    int maxLength();              // Max identifier length (30, 63, 64, 128 etc.)
    String quote(String raw);     // Identifier quoting (`foo`, "foo", [foo])
    String normalizeCase(String raw);  // Case normalization (Oracle → toUpperCase)
    boolean isKeyword(String raw);     // Reserved word check
}

// Type mapping
public interface JavaTypeMapper {
    JavaType map(String className);

    interface JavaType {
        String getSqlType(int length, int precision, int scale);
        boolean needsQuotes();
        String getDefaultValue();
    }
}

// Value transformation
public interface ValueTransformer {
    String quote(String value, JavaTypeMapper.JavaType type);
}
```

## Step-by-Step Implementation Guide

### 1. Create Package Structure

Create packages for the new dialect:

```
jinx-core/src/main/java/org/jinx/migration/dialect/postgresql/
├── PostgreSqlDialect.java
├── PostgreSqlIdentifierPolicy.java
├── PostgreSqlJavaTypeMapper.java
├── PostgreSqlValueTransformer.java
├── PostgreSqlMigrationVisitor.java
└── PostgreSqlUtil.java (if needed)
```

### 2. Test Package Structure

```
jinx-core/src/test/java/org/jinx/migration/dialect/postgresql/
├── PostgreSqlDialectTest.java
├── PostgreSqlIdentifierPolicyTest.java
├── PostgreSqlJavaTypeMapperTest.java
├── PostgreSqlValueTransformerTest.java
└── PostgreSqlMigrationVisitorTest.java
```

## SPI Interface Implementation

### 1. IdentifierPolicy Implementation

```java
package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.IdentifierPolicy;
import java.util.Set;

public class PostgreSqlIdentifierPolicy implements IdentifierPolicy {

    // PostgreSQL reserved words
    private static final Set<String> KEYWORDS = Set.of(
        "select", "from", "where", "insert", "update", "delete",
        "create", "drop", "alter", "table", "column", "index",
        // ... PostgreSQL keywords
    );

    @Override
    public int maxLength() {
        return 63; // PostgreSQL identifier limit
    }

    @Override
    public String quote(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // PostgreSQL uses double quotes
        return "\"" + raw + "\"";
    }

    @Override
    public String normalizeCase(String raw) {
        if (raw == null) return null;
        // PostgreSQL normalizes to lowercase (case insensitive)
        return raw.toLowerCase();
    }

    @Override
    public boolean isKeyword(String raw) {
        return raw != null && KEYWORDS.contains(raw.toLowerCase());
    }
}
```

### 2. JavaTypeMapper Implementation

```java
package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.JavaTypeMapper;

public class PostgreSqlJavaTypeMapper implements JavaTypeMapper {

    @Override
    public JavaType map(String className) {
        return switch (className) {
            case "java.lang.String" -> new PostgreSqlStringType();
            case "int", "java.lang.Integer" -> new PostgreSqlIntegerType();
            case "long", "java.lang.Long" -> new PostgreSqlBigIntType();
            case "java.math.BigDecimal" -> new PostgreSqlDecimalType();
            case "boolean", "java.lang.Boolean" -> new PostgreSqlBooleanType();
            case "java.time.LocalDate" -> new PostgreSqlDateType();
            case "java.time.LocalDateTime" -> new PostgreSqlTimestampType();
            case "java.util.UUID" -> new PostgreSqlUuidType();
            case "byte[]" -> new PostgreSqlByteArrayType();
            // ... other types
            default -> new PostgreSqlStringType(); // fallback
        };
    }

    // Inner type classes
    private static class PostgreSqlStringType implements JavaType {
        @Override
        public String getSqlType(int length, int precision, int scale) {
            return length > 0 ? "VARCHAR(" + length + ")" : "TEXT";
        }

        @Override
        public boolean needsQuotes() { return true; }

        @Override
        public String getDefaultValue() { return null; }
    }

    private static class PostgreSqlUuidType implements JavaType {
        @Override
        public String getSqlType(int length, int precision, int scale) {
            return "UUID";
        }

        @Override
        public boolean needsQuotes() { return false; }

        @Override
        public String getDefaultValue() { return null; }
    }

    // ... other type classes
}
```

### 3. ValueTransformer Implementation

```java
package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;

public class PostgreSqlValueTransformer implements ValueTransformer {

    @Override
    public String quote(String value, JavaTypeMapper.JavaType type) {
        if (value == null) {
            return "NULL";
        }

        if (type.needsQuotes()) {
            // String types: wrap with single quotes and escape
            return "'" + value.replace("'", "''") + "'";
        } else {
            // Numbers, booleans etc.: return as-is
            return value;
        }
    }
}
```

### 4. Main Dialect Class Implementation

```java
package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.AbstractDialect;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.*;
import org.jinx.model.*;

public class PostgreSqlDialect extends AbstractDialect
        implements SequenceDialect, LiquibaseDialect {

    public PostgreSqlDialect() {
        super();
    }

    @Override
    protected JavaTypeMapper initializeJavaTypeMapper() {
        return new PostgreSqlJavaTypeMapper();
    }

    @Override
    protected ValueTransformer initializeValueTransformer() {
        return new PostgreSqlValueTransformer();
    }

    // BaseDialect implementation
    @Override
    public String quoteIdentifier(String raw) {
        return "\"" + raw + "\"";
    }

    // DdlDialect implementation
    @Override
    public String openCreateTable(String tableName) {
        return "CREATE TABLE " + quoteIdentifier(tableName) + " (\n";
    }

    @Override
    public String closeCreateTable() {
        return "\n);";
    }

    @Override
    public String getColumnDefinitionSql(ColumnModel column) {
        StringBuilder sb = new StringBuilder();

        String javaType = column.getConversionClass() != null ?
            column.getConversionClass() : column.getJavaType();
        JavaTypeMapper.JavaType mappedType = javaTypeMapper.map(javaType);

        String sqlType;
        if (column.getSqlTypeOverride() != null && !column.getSqlTypeOverride().trim().isEmpty()) {
            sqlType = column.getSqlTypeOverride().trim();
        } else {
            sqlType = mappedType.getSqlType(column.getLength(), column.getPrecision(), column.getScale());
        }

        sb.append(quoteIdentifier(column.getColumnName())).append(" ").append(sqlType);

        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }

        // PostgreSQL supports SERIAL type
        if (shouldUseSerial(column.getGenerationStrategy())) {
            // SERIAL type is already included in sqlType
        }

        if (column.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(column.getDefaultValue(), mappedType));
        }

        return sb.toString();
    }

    // SequenceDialect implementation (PostgreSQL supports sequences)
    @Override
    public String getCreateSequenceSql(SequenceModel sequence) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE SEQUENCE ").append(quoteIdentifier(sequence.getSequenceName()));

        if (sequence.getInitialValue() > 1) {
            sb.append(" START WITH ").append(sequence.getInitialValue());
        }

        if (sequence.getAllocationSize() > 1) {
            sb.append(" INCREMENT BY ").append(sequence.getAllocationSize());
        }

        sb.append(";\n");
        return sb.toString();
    }

    // PostgreSQL-specific methods
    private boolean shouldUseSerial(GenerationStrategy strategy) {
        return strategy == GenerationStrategy.IDENTITY || strategy == GenerationStrategy.AUTO;
    }

    @Override
    public int getMaxIdentifierLength() {
        return 63; // PostgreSQL limit
    }

    // ... other DdlDialect method implementations
}
```

## Dialect Bundle Configuration

### Add to DatabaseType Enum

```java
// jinx-core/src/main/java/org/jinx/migration/DatabaseType.java
public enum DatabaseType {
    MYSQL,
    POSTGRESQL,  // newly added
    // ... other databases
}
```

### DialectBundle Creation Helper

```java
// Example PostgreSQL dialect bundle creation
public static DialectBundle createPostgreSqlBundle() {
    PostgreSqlDialect dialect = new PostgreSqlDialect();

    return DialectBundle.builder(dialect, DatabaseType.POSTGRESQL)
            .sequence(dialect)      // PostgreSQL supports sequences
            .liquibase(dialect)     // Liquibase support
            .build();
}
```

## VisitorFactory Registration

Add new dialect case to `VisitorFactory.java`:

```java
// jinx-core/src/main/java/org/jinx/migration/VisitorFactory.java
public final class VisitorFactory {
    public static VisitorProviders forBundle(DialectBundle bundle) {
        var db = bundle.databaseType();
        var ddl = bundle.ddl();

        switch (db) {
            case MYSQL -> {
                // existing MySQL code
            }
            case POSTGRESQL -> {  // newly added
                Supplier<TableVisitor> tableV =
                        () -> new PostgreSqlMigrationVisitor(null, ddl);

                Function<DiffResult.ModifiedEntity, TableContentVisitor> contentV =
                        me -> new PostgreSqlMigrationVisitor(me, ddl);

                // PostgreSQL supports sequences
                Optional<Supplier<SequenceVisitor>> seqV = bundle.sequence().map(seqDialect ->
                        (Supplier<SequenceVisitor>) () -> new PostgreSqlSequenceVisitor(seqDialect)
                );

                // TableGenerator is optional (PostgreSQL prefers sequences)
                var tgOpt = bundle.tableGenerator().map(tgDialect ->
                        (Supplier<TableGeneratorVisitor>) () -> new PostgreSqlTableGeneratorVisitor(tgDialect)
                );

                return new VisitorProviders(tableV, contentV, seqV, tgOpt);
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + db);
        }
    }
}
```

## CLI Integration

Add to `resolveDialects` method in `MigrateCommand.java`:

```java
// jinx-cli/src/main/java/org/jinx/cli/MigrateCommand.java
private DialectBundle resolveDialects(String name) {
    return switch (name.toLowerCase()) {
        case "mysql" -> {
            MySqlDialect mysql = new MySqlDialect();
            yield DialectBundle.builder(mysql, DatabaseType.MYSQL)
                    .identity(mysql)
                    .tableGenerator(mysql)
                    .build();
        }
        case "postgresql", "postgres" -> {  // newly added
            PostgreSqlDialect postgres = new PostgreSqlDialect();
            yield DialectBundle.builder(postgres, DatabaseType.POSTGRESQL)
                    .sequence(postgres)      // sequence support
                    .liquibase(postgres)     // Liquibase support
                    .build();
        }
        default -> throw new IllegalArgumentException("Unsupported dialect: " + name);
    };
}
```

## Writing Tests

### 1. Unit Tests

Write unit tests for each component:

```java
// PostgreSqlDialectTest.java
@Test
@DisplayName("PostgreSQL column definition SQL generation")
void testColumnDefinitionSql() {
    PostgreSqlDialect dialect = new PostgreSqlDialect();

    ColumnModel column = ColumnModel.builder()
            .columnName("username")
            .javaType("java.lang.String")
            .length(255)
            .isNullable(false)
            .build();

    String sql = dialect.getColumnDefinitionSql(column);
    assertThat(sql).isEqualTo("\"username\" VARCHAR(255) NOT NULL");
}

@Test
@DisplayName("PostgreSQL sequence creation SQL")
void testCreateSequenceSql() {
    PostgreSqlDialect dialect = new PostgreSqlDialect();

    SequenceModel sequence = SequenceModel.builder()
            .sequenceName("user_id_seq")
            .initialValue(1)
            .allocationSize(1)
            .build();

    String sql = dialect.getCreateSequenceSql(sequence);
    assertThat(sql).isEqualTo("CREATE SEQUENCE \"user_id_seq\";\n");
}
```

### 2. Integration Tests

```java
// PostgreSqlIntegrationTest.java
@Test
@DisplayName("PostgreSQL full migration generation test")
void testFullMigrationGeneration() {
    // Create entity model
    EntityModel entity = createTestEntity();

    // Create dialect bundle
    DialectBundle bundle = createPostgreSqlBundle();

    // Generate migration
    MigrationGenerator generator = new MigrationGenerator(bundle);
    DiffResult diff = createTestDiff(entity);

    String sql = generator.generateSql(diff);

    // Verify generated SQL
    assertThat(sql).contains("CREATE TABLE");
    assertThat(sql).contains("VARCHAR");
    assertThat(sql).doesNotContain("AUTO_INCREMENT"); // PostgreSQL uses SERIAL
}
```

### 3. Required Test Coverage

**You MUST test the following areas:**

1. **Identifier Policy Tests**
   - Maximum length limits
   - Quoting syntax
   - Case normalization
   - Reserved word validation

2. **Type Mapping Tests**
   - SQL type mapping for all Java types
   - Length, precision, scale handling
   - Default value handling

3. **DDL Generation Tests**
   - CREATE TABLE
   - ALTER TABLE (ADD/DROP/MODIFY COLUMN)
   - Constraints (PK, FK, UNIQUE, CHECK)
   - Indexes

4. **Specialized Feature Tests**
   - Sequences (if supported)
   - Identity/Serial columns
   - TableGenerator (if needed)

## Real Example: Adding PostgreSQL Dialect

Here's an actual step-by-step checklist for adding PostgreSQL dialect:

### Step 1: Create Basic Structure

```bash
# 1. Create package directories
mkdir -p jinx-core/src/main/java/org/jinx/migration/dialect/postgresql
mkdir -p jinx-core/src/test/java/org/jinx/migration/dialect/postgresql

# 2. Create basic class files
touch jinx-core/src/main/java/org/jinx/migration/dialect/postgresql/PostgreSqlDialect.java
touch jinx-core/src/main/java/org/jinx/migration/dialect/postgresql/PostgreSqlJavaTypeMapper.java
touch jinx-core/src/main/java/org/jinx/migration/dialect/postgresql/PostgreSqlValueTransformer.java
touch jinx-core/src/main/java/org/jinx/migration/dialect/postgresql/PostgreSqlMigrationVisitor.java
```

### Step 2: Implement Interfaces

1. Implement `PostgreSqlJavaTypeMapper`
2. Implement `PostgreSqlValueTransformer`
3. Implement `PostgreSqlDialect` main class
4. Implement `PostgreSqlMigrationVisitor`

### Step 3: System Integration

1. Add `DatabaseType.POSTGRESQL`
2. Add PostgreSQL case to `VisitorFactory`
3. Add to `MigrateCommand.resolveDialects()`

### Step 4: Write Tests

1. Write unit tests for each class
2. Write integration tests
3. Verify actual PostgreSQL DDL

### Step 5: Documentation

1. Add PostgreSQL to supported DB list
2. Update usage documentation
3. Update this contribution guide

## Important Considerations

### Must-Have Considerations

1. **Reserved Word Handling**: Accurately identify reserved words for each DB
2. **Identifier Length**: Respect DB-specific maximum identifier length limits
3. **Type Mapping**: Accurate mapping between Java types and SQL types
4. **Syntax Differences**: CREATE TABLE, ALTER TABLE syntax differences
5. **Constraints**: PK, FK, UNIQUE, CHECK constraint syntax
6. **Specialized Features**: Support for sequences, auto-increment columns etc.

### Testing Requirements

1. **DDL Syntax Validation**: Verify generated SQL is executable on actual DB
2. **Migration Testing**: Test real schema change scenarios
3. **Backward Compatibility**: Ensure no impact on existing code
4. **Error Cases**: Error handling for invalid configurations or unsupported features

### Performance Considerations

1. **Lazy Initialization**: Initialize heavy resources only when needed
2. **Caching**: Cache frequently used information
3. **Memory Efficiency**: Minimize unnecessary object creation

---

Adding a new database dialect is complex, but Jinx's well-designed SPI architecture makes it possible to approach it systematically. By following the steps in this guide and referencing the existing MySQL implementation, you can successfully add new dialect support.

**Essential points to remember when contributing:**
- Write **comprehensive tests** for all changes
- **Validate DDL** on actual databases
- **Update documentation**
- Request **code review**
- All tests must pass in CI pipeline before merge approval

Your contributions will make Jinx an even more powerful tool!