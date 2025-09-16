# Jinx Gradle Plugin

Gradle plugin for Jinx JPA DDL generation tool.

## Usage

### Apply Plugin

```gradle
plugins {
    id 'org.jinx.gradle' version '1.0-SNAPSHOT'
}

dependencies {
    annotationProcessor project(':jinx-processor')
}
```

### Basic Configuration

```gradle
jinx {
    profile = 'prod'

    naming {
        maxLength = 63
    }

    database {
        dialect = 'postgresql'
    }

    output {
        directory = 'src/main/resources/db/migration'
        format = 'liquibase'
    }
}
```

### Configuration Priority

The plugin follows this priority order:

1. **Gradle DSL**: Explicit configuration in `build.gradle`
2. **YAML File**: `jinx.yaml` in project directory with specified profile
3. **Environment Variables**: `JINX_PROFILE`
4. **Default Values**: Built-in defaults

### YAML Configuration File

Create `jinx.yaml` in your project root:

```yaml
profiles:
  dev:
    naming:
      maxLength: 30
    database:
      dialect: mysql

  prod:
    naming:
      maxLength: 63
    database:
      dialect: postgresql

  test:
    naming:
      maxLength: 20
    database:
      dialect: h2
```

### Tasks

The plugin automatically creates the following tasks:

- **`jinxMigrate`**: Generate database migration files

### Examples

#### Profile Selection

```bash
# Via Gradle property
./gradlew compileJava -Djinx.profile=prod

# Via environment variable
JINX_PROFILE=prod ./gradlew compileJava

# Via DSL
jinx {
    profile = 'prod'
}
```

#### Automatic Compiler Options

The plugin automatically adds `-A` options to JavaCompile tasks:

```bash
# Generated compiler arguments
javac -Ajinx.naming.maxLength=63 -Ajinx.profile=prod ...
```

#### Migration Generation

```bash
./gradlew jinxMigrate
```

This is equivalent to:

```bash
java -cp ... org.jinx.cli.JinxCli db migrate --profile prod --max-length 63
```

## Integration

### With Annotation Processor

The plugin works seamlessly with the Jinx annotation processor. Configuration is automatically applied during compilation.

### With CLI

Use the `jinxMigrate` task or run CLI directly with consistent configuration from YAML files and Gradle DSL.