package org.jinx.descriptor;

import com.google.testing.compile.Compilation;
import org.jinx.processor.AbstractProcessorTest;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldAttributeDescriptor 테스트
 *
 * 필드 기반 접근 방식의 속성 디스크립터 동작을 검증합니다.
 */
class FieldAttributeDescriptorTest extends AbstractProcessorTest {

    @Test
    void testFieldAccessWithBasicTypes() {
        JavaFileObject source = forSourceLines(
                "test.User",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class User {",
                "    @Id",
                "    private Long id;",
                "    ",
                "    @Column(name = \"user_name\")",
                "    private String name;",
                "    ",
                "    private Integer age;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            assertEquals(1, schema.getEntities().size());
            var entity = schema.getEntities().values().iterator().next();

            // 필드들이 올바르게 처리되었는지 확인
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("user_name")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("age")));
        });
    }

    @Test
    void testFieldAccessWithEnumType() {
        JavaFileObject source = forSourceLines(
                "test.Product",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class Product {",
                "    @Id",
                "    private Long id;",
                "    ",
                "    @Enumerated(EnumType.STRING)",
                "    @Column(name = \"status\")",
                "    private Status status;",
                "    ",
                "    public enum Status {",
                "        ACTIVE, INACTIVE",
                "    }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("status")));
        });
    }

    @Test
    void testFieldAccessWithGeneratedValue() {
        JavaFileObject source = forSourceLines(
                "test.AutoGenEntity",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class AutoGenEntity {",
                "    @Id",
                "    @GeneratedValue(strategy = GenerationType.IDENTITY)",
                "    private Long id;",
                "    ",
                "    private String data;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();
            assertNotNull(entity);
            // GeneratedValue가 올바르게 처리되었는지 확인
            assertNotNull(entity.getColumns());
        });
    }

    @Test
    void testFieldAccessWithMultipleColumns() {
        JavaFileObject source = forSourceLines(
                "test.Employee",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class Employee {",
                "    @Id",
                "    private Long id;",
                "    ",
                "    @Column(name = \"first_name\")",
                "    private String firstName;",
                "    ",
                "    @Column(name = \"last_name\")",
                "    private String lastName;",
                "    ",
                "    private String email;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // 여러 컬럼들이 올바르게 처리되었는지 확인
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("first_name")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("last_name")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("email")));
        });
    }
}
