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

    @Test
    void findPrimaryKeyColumnName_withEmptyColumnsMap_returnsEmpty() {
        EntityModel entity = EntityModel.builder()
                .entityName("EmptyEntity")
                .build();

        SchemaModel schema = SchemaModel.builder().build();
        ProcessingEnvironment mockEnv = Mockito.mock(ProcessingEnvironment.class);
        ProcessingContext ctx = new ProcessingContext(mockEnv, schema);

        Optional<String> colName = ctx.findPrimaryKeyColumnName(entity);
        assertThat(colName.isEmpty()).isTrue();
    }

    @Test
    void findPrimaryKeyColumnName_handlesCompositeKeyReturnsOneKey() {
        ColumnModel pk1 = ColumnModel.builder()
                .columnName("order_id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build();
        ColumnModel pk2 = ColumnModel.builder()
                .columnName("line_no")
                .javaType("java.lang.Integer")
                .isPrimaryKey(true)
                .build();
        ColumnModel data = ColumnModel.builder()
                .columnName("sku")
                .javaType("java.lang.String")
                .isPrimaryKey(false)
                .build();

        EntityModel entity = EntityModel.builder()
                .entityName("OrderLine")
                .build();
        entity.putColumn(pk1);
        entity.putColumn(pk2);
        entity.putColumn(data);

        SchemaModel schema = SchemaModel.builder().build();
        ProcessingEnvironment mockEnv = Mockito.mock(ProcessingEnvironment.class);
        ProcessingContext ctx = new ProcessingContext(mockEnv, schema);

        Optional<String> colName = ctx.findPrimaryKeyColumnName(entity);
        assertThat(colName).isPresent();
        assertThat(colName.get()).isAnyOf("order_id", "line_no");
    }

    @Test
    void findPrimaryKeyColumnName_supportsCustomPrimaryKeyName() {
        ColumnModel pk = ColumnModel.builder()
                .columnName("user_pk")
                .javaType("java.util.UUID")
                .isPrimaryKey(true)
                .build();
        ColumnModel other = ColumnModel.builder()
                .columnName("email")
                .javaType("java.lang.String")
                .isPrimaryKey(false)
                .build();

        EntityModel entity = EntityModel.builder()
                .entityName("Account")
                .build();
        entity.putColumn(pk);
        entity.putColumn(other);

        SchemaModel schema = SchemaModel.builder().build();
        ProcessingEnvironment mockEnv = Mockito.mock(ProcessingEnvironment.class);
        ProcessingContext ctx = new ProcessingContext(mockEnv, schema);

        Optional<String> colName = ctx.findPrimaryKeyColumnName(entity);
        assertThat(colName).isPresent();
        assertThat(colName.get()).isEqualTo("user_pk");
    }

    @Test
    void findPrimaryKeyColumnName_returnsEmptyWhenNoPrimaryKey() {
        ColumnModel col = ColumnModel.builder()
                .columnName("user_id")
                .javaType("java.lang.Long")
                .isPrimaryKey(false)
                .build();

        EntityModel entity = EntityModel.builder()
                .entityName("User")
                .build();
        entity.putColumn(col);

        SchemaModel schema = SchemaModel.builder().build();
        ProcessingEnvironment mockEnv = Mockito.mock(ProcessingEnvironment.class);
        ProcessingContext ctx = new ProcessingContext(mockEnv, schema);

        Optional<String> colName = ctx.findPrimaryKeyColumnName(entity);
        assertThat(colName.isEmpty()).isTrue();
    }

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

        // Compile another entity with a UUID primary key to broaden processor coverage
        JavaFileObject accountEntity = JavaFileObjects.forSourceLines(
                "com.example.Account",
                "package com.example;",
                "import jakarta.persistence.*;",
                "import java.util.UUID;",
                "@Entity public class Account { @Id UUID id; String email; }"
        );
        Compilation c2 = Compiler.javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(accountEntity);
        assertThat(c2.status()).isEqualTo(Compilation.Status.SUCCESS);
        boolean generated2 = c2.generatedFiles().stream()
                .anyMatch(f -> f.getName().contains("/jinx/") && f.getName().endsWith(".json"));
        assertThat(generated2).isTrue();

        // Additionally ensure at least one generated JSON has non-empty content
        Optional<JavaFileObject> anyJson = c.generatedFiles().stream()
                .filter(f -> f.getName().contains("/jinx/") && f.getName().endsWith(".json"))
                .findFirst();
        assertThat(anyJson).isPresent();
        try {
            String text = anyJson.get().getCharContent(true).toString();
            assertThat(text).isNotEmpty();
        } catch (Exception e) {
            throw new AssertionError("Failed to read generated JSON content", e);
        }
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
                .build();
        entity.putColumn(idCol);

        SchemaModel schema = SchemaModel.builder().build();
        ProcessingEnvironment mockEnv = Mockito.mock(ProcessingEnvironment.class);
        ProcessingContext ctx = new ProcessingContext(mockEnv, schema);

        Optional<String> colName = ctx.findPrimaryKeyColumnName(entity);
        assertThat(colName.get()).isEqualTo("id");
    }
}
