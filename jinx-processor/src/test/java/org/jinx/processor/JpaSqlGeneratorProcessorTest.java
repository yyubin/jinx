package org.jinx.processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

class JpaSqlGeneratorProcessorTest {

    @Test
    void processorProducesValidSchemaModel() throws Exception {
        JavaFileObject userEntity = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity",
                "public class User {",
                "  @Id Long id;",
                "  String name;",
                "}"
        );

        Compilation compilation = Compiler.javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(userEntity);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);

        /* ---------- JSON 파일 탐색 로직 ---------- */
        List<JavaFileObject> jsonFiles =
                compilation.generatedFiles().stream()       // 모든 location 통합
                        .filter(f -> f.getName().endsWith(".json"))
                        .filter(f -> f.getName().contains("schema"))
                        .collect(Collectors.toList());

        assertThat(jsonFiles).isNotEmpty();                // 최소 1개는 나와야 함
        JavaFileObject jsonFile = jsonFiles.get(0);        // 첫 번째 파일로 검증
        /* ----------------------------------------- */

        String json;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(jsonFile.openInputStream(), StandardCharsets.UTF_8))) {
            json = reader.lines().collect(Collectors.joining("\n"));
        }

        ObjectMapper mapper = JpaSqlGeneratorProcessor.OBJECT_MAPPER
                .copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});

        assertThat(root).containsKey("version");

        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) root.get("entities");
        assertThat(entities).containsKey("com.example.User");
    }
}
