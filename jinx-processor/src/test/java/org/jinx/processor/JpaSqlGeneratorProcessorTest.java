package org.jinx.processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

class JpaSqlGeneratorProcessorTest {

    private static Map<String, Object> readSchemaRoot(Compilation comp) throws Exception {
        JavaFileObject jsonFile = comp.generatedFiles().stream()
                .filter(f -> f.getName().endsWith(".json") && f.getName().contains("schema"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("schema file not generated"));

        String json;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(jsonFile.openInputStream(), StandardCharsets.UTF_8))) {
            json = br.lines().collect(Collectors.joining("\n"));
        }

        ObjectMapper mapper = JpaSqlGeneratorProcessor.OBJECT_MAPPER.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper.readValue(json, new TypeReference<>() {});
    }

    @Test
    @DisplayName("기본 @Entity → schema.json 생성")
    void basicEntityProducesSchema() throws Exception {
        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User {",
                "  @Id Long id;",
                "  String name;",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        assertThat(root).containsKey("version");

        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) root.get("entities");
        assertThat(entities).containsKey("com.example.User");
    }

    @Test
    @DisplayName("@Converter(autoApply=true) 적용")
    void handlesAutoApplyConverter() throws Exception {
        JavaFileObject conv = JavaFileObjects.forSourceLines(
                "com.example.BoolYnConv",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Converter(autoApply=true)",
                "public class BoolYnConv implements AttributeConverter<Boolean,String>{",
                "  public String convertToDatabaseColumn(Boolean a){return a==null?null:(a?\"Y\":\"N\");}",
                "  public Boolean convertToEntityAttribute(String d){return \"Y\".equals(d);}}");

        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity public class User{",
                "  @Id Long id;",
                "  Boolean active;",
                "}");

        Compilation c = Compiler.javac()
                .withOptions("--add-opens",
                        "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(conv, user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>)
                ((Map<?,?>)((Map<?,?>)root.get("entities"))
                        .get("com.example.User"))
                        .get("columns");

        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) columns.get("active");
        assertThat(active.get("conversionClass"))
                .isEqualTo("com.example.BoolYnConv");
    }

    @Test
    @DisplayName("@MappedSuperclass · @Embeddable 필드 병합")
    void handlesMappedSuperclassAndEmbeddable() throws Exception {
        JavaFileObject base = JavaFileObjects.forSourceLines(
                "com.example.Base",
                "package com.example;",
                "import jakarta.persistence.*;",
                "import java.time.*;",
                "@MappedSuperclass",
                "public class Base{",
                "  @Column(name=\"created_at\") LocalDateTime createdAt; }");

        JavaFileObject addr = JavaFileObjects.forSourceLines(
                "com.example.Address",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Embeddable",
                "public class Address{ String city; String street; }");

        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User extends Base{",
                "  @Id Long id;",
                "  @Embedded Address address; }");

        Compilation c = Compiler.javac()
                .withOptions("--add-opens",
                        "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(base, addr, user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> cols = (Map<String, Object>)
                ((Map<?,?>)((Map<?,?>)root.get("entities"))
                        .get("com.example.User"))
                        .get("columns");

        assertThat(cols).containsKey("created_at"); // MappedSuperclass
        assertThat(cols).containsKey("city");       // Embeddable
        assertThat(cols).containsKey("street");     // Embeddable
    }

    /*
     * Additional unit tests appended to increase coverage of JPA mapping scenarios.
     * Testing stack: JUnit 5 (Jupiter), Google Truth assertions, and Google Compile Testing.
     * Focus: schema column derivation, field exclusions, attribute/column overrides, and converter precedence.
     */

    @Test
    @DisplayName("@Transient fields are excluded from schema columns")
    void transientFieldsExcludedFromSchema() throws Exception {
        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User {",
                "  @Id Long id;",
                "  @Transient String temp;",
                "  String name;",
                "}"
        );

        Compilation c = Compiler.javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>)
                ((Map<?,?>)((Map<?,?>)root.get("entities"))
                        .get("com.example.User"))
                        .get("columns");

        // Ensure transient field is not exported while a regular field is present.
        assertThat(columns.containsKey("temp")).isFalse();
        assertThat(columns.containsKey("name")).isTrue();
    }

    @Test
    @DisplayName("@Column(name=...) is respected when deriving column keys")
    void columnAnnotationOverridesName() throws Exception {
        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User {",
                "  @Id Long id;",
                "  @Column(name=\"full_name\") String name;",
                "}"
        );

        Compilation c = Compiler.javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>)
                ((Map<?,?>)((Map<?,?>)root.get("entities"))
                        .get("com.example.User"))
                        .get("columns");

        // The overridden column name should be used as the key.
        assertThat(columns.containsKey("full_name")).isTrue();
    }

    @Test
    @DisplayName("@AttributeOverride on @Embedded field renames embedded column(s)")
    void attributeOverrideOnEmbeddedField() throws Exception {
        JavaFileObject addr = JavaFileObjects.forSourceLines(
                "com.example.Address",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Embeddable",
                "public class Address {",
                "  String city;",
                "  String street;",
                "}"
        );

        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User {",
                "  @Id Long id;",
                "  @Embedded",
                "  @AttributeOverrides({",
                "    @AttributeOverride(name=\"street\", column=@Column(name=\"str_name\"))",
                "  })",
                "  Address address;",
                "}"
        );

        Compilation c = Compiler.javac()
                .withOptions("--add-opens",
                        "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(addr, user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>)
                ((Map<?,?>)((Map<?,?>)root.get("entities"))
                        .get("com.example.User"))
                        .get("columns");

        // Ensure both a default embedded column and an overridden column are present.
        assertThat(columns.containsKey("city")).isTrue();
        assertThat(columns.containsKey("str_name")).isTrue();
    }

    @Test
    @DisplayName("Field-level @Convert overrides @Converter(autoApply=true)")
    void fieldLevelConvertOverridesAutoApply() throws Exception {
        JavaFileObject convYn = JavaFileObjects.forSourceLines(
                "com.example.BoolYnConv",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Converter(autoApply=true)",
                "public class BoolYnConv implements AttributeConverter<Boolean,String>{",
                "  public String convertToDatabaseColumn(Boolean a){return a==null?null:(a?\"Y\":\"N\");}",
                "  public Boolean convertToEntityAttribute(String d){return \"Y\".equals(d);} }"
        );

        JavaFileObject conv10 = JavaFileObjects.forSourceLines(
                "com.example.Bool10Conv",
                "package com.example;",
                "import jakarta.persistence.*;",
                "public class Bool10Conv implements AttributeConverter<Boolean,String>{",
                "  public String convertToDatabaseColumn(Boolean a){return a==null?null:(a?\"1\":\"0\");}",
                "  public Boolean convertToEntityAttribute(String d){return \"1\".equals(d);} }"
        );

        JavaFileObject user = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User {",
                "  @Id Long id;",
                "  @Convert(converter=Bool10Conv.class)",
                "  Boolean active;",
                "}"
        );

        Compilation c = Compiler.javac()
                .withOptions("--add-opens",
                        "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(convYn, conv10, user);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        Map<String, Object> root = readSchemaRoot(c);
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>)
                ((Map<?,?>)((Map<?,?>)root.get("entities"))
                        .get("com.example.User"))
                        .get("columns");

        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) columns.get("active");
        assertThat(active.get("conversionClass"))
                .isEqualTo("com.example.Bool10Conv");
    }

}
