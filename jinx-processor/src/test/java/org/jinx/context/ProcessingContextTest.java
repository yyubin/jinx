package org.jinx.context;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.jinx.model.*;
import org.jinx.processor.JpaSqlGeneratorProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.util.Map;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

class ProcessingContextTest {

    // saveModelToJson 통합 테스트 (Processor 경유)
    @Test
    void saveModelToJson_writesFileUnderJinxFolder() {
        JavaFileObject userEntity = JavaFileObjects.forSourceLines(
                "com.example.User",
                "package com.example;",
                "import jakarta.persistence.*;",
                "@Entity public class User { @Id Long id; }"
        );

        Compilation c = Compiler.javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(userEntity);

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);

        boolean generated = c.generatedFiles().stream()
                .anyMatch(f ->
                        f.getName().contains("/jinx/") &&
                        f.getName().endsWith(".json"));
        assertThat(generated).isTrue();
    }

    // findPrimaryKeyColumnName 단위 테스트
    @Test
    void findPrimaryKeyColumnName_returnsIdColumn() {
        ColumnModel idCol = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build();

        EntityModel entity = EntityModel.builder()
                .entityName("Test")
                .columns(Map.of("id", idCol))
                .build();

        SchemaModel schema = SchemaModel.builder().build();
        ProcessingEnvironment mockEnv = Mockito.mock(ProcessingEnvironment.class);
        ProcessingContext ctx = new ProcessingContext(mockEnv, schema);

        Optional<String> colName = ctx.findPrimaryKeyColumnName(entity);
        assertThat(colName.get()).isEqualTo("id");
    }
}
