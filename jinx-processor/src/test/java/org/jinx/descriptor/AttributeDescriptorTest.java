package org.jinx.descriptor;

import com.google.testing.compile.Compilation;
import org.jinx.processor.AbstractProcessorTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.*;

class AttributeDescriptorTest extends AbstractProcessorTest {

    @Test
    @DisplayName("findAnnotationMirror는 FQCN으로 어노테이션을 찾을 수 있다")
    void findAnnotationMirror_byFqcn() {
        JavaFileObject source = forSourceLines(
                "test.User",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class User {",
                "    @Id",
                "    @Column(name = \"user_id\")",
                "    private Long id;",
                "    ",
                "    @Column(name = \"user_name\", length = 100)",
                "    private String name;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            assertEquals(1, schema.getEntities().size());

            var entity = schema.getEntities().values().iterator().next();

            // Column 어노테이션이 올바르게 처리되었는지 확인
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("user_id")));
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("user_name")));
        });
    }

    @Test
    @DisplayName("hasAnnotation(String)은 FQCN으로 어노테이션 존재를 확인할 수 있다")
    void hasAnnotation_byFqcnString() {
        JavaFileObject source = forSourceLines(
                "test.Product",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class Product {",
                "    @Id",
                "    @GeneratedValue(strategy = GenerationType.IDENTITY)",
                "    private Long id;",
                "    ",
                "    @Column(nullable = false)",
                "    private String name;",
                "    ",
                "    private String description;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // GeneratedValue가 있는 컬럼 확인
            var idColumn = entity.getColumns().values().stream()
                    .filter(c -> c.getColumnName().equals("id"))
                    .findFirst();
            assertTrue(idColumn.isPresent());

            // nullable=false인 컬럼 확인
            var nameColumn = entity.getColumns().values().stream()
                    .filter(c -> c.getColumnName().equals("name"))
                    .findFirst();
            assertTrue(nameColumn.isPresent());
            assertFalse(nameColumn.get().isNullable());
        });
    }

    @Test
    @DisplayName("어노테이션이 없는 필드에 대해 findAnnotationMirror는 empty를 반환한다")
    void findAnnotationMirror_returnsEmptyWhenNotPresent() {
        JavaFileObject source = forSourceLines(
                "test.SimpleEntity",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class SimpleEntity {",
                "    @Id",
                "    private Long id;",
                "    ",
                "    private String plainField;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // plainField는 Column 어노테이션이 없어도 기본값으로 처리됨
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("plainField")));
        });
    }

    @Test
    @DisplayName("복수의 어노테이션이 있는 필드를 올바르게 처리한다")
    void multipleAnnotations_onSameField() {
        JavaFileObject source = forSourceLines(
                "test.ComplexEntity",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class ComplexEntity {",
                "    @Id",
                "    @GeneratedValue(strategy = GenerationType.AUTO)",
                "    @Column(name = \"entity_id\", nullable = false)",
                "    private Long id;",
                "    ",
                "    @Column(name = \"entity_name\", length = 200, unique = true)",
                "    private String name;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // id 필드: GeneratedValue + Column 모두 있음
            var idColumn = entity.getColumns().values().stream()
                    .filter(c -> c.getColumnName().equals("entity_id"))
                    .findFirst();
            assertTrue(idColumn.isPresent());
            assertFalse(idColumn.get().isNullable());

            // name 필드: Column만 있음
            var nameColumn = entity.getColumns().values().stream()
                    .filter(c -> c.getColumnName().equals("entity_name"))
                    .findFirst();
            assertTrue(nameColumn.isPresent());
            assertEquals(200, nameColumn.get().getLength());
        });
    }

    @Test
    @DisplayName("상속 관계에서 부모 클래스의 어노테이션도 올바르게 처리한다")
    void annotationsInInheritance() {
        JavaFileObject baseSource = forSourceLines(
                "test.BaseEntity",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@MappedSuperclass",
                "public abstract class BaseEntity {",
                "    @Id",
                "    @GeneratedValue",
                "    private Long id;",
                "    ",
                "    @Column(name = \"created_at\")",
                "    private java.time.LocalDateTime createdAt;",
                "}"
        );

        JavaFileObject childSource = forSourceLines(
                "test.Article",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class Article extends BaseEntity {",
                "    @Column(name = \"title\")",
                "    private String title;",
                "    ",
                "    @Column(name = \"content\", columnDefinition = \"TEXT\")",
                "    private String content;",
                "}"
        );

        Compilation compilation = compile(baseSource, childSource);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().get("test.Article");
            assertNotNull(entity);

            // 부모의 필드도 포함되어야 함
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("created_at")));

            // 자식의 필드
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("title")));
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("content")));
        });
    }

    @Test
    @DisplayName("Enumerated 어노테이션의 FQCN 조회가 정상 동작한다")
    void enumeratedAnnotation_fqcnLookup() {
        JavaFileObject source = forSourceLines(
                "test.Order",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class Order {",
                "    @Id",
                "    private Long id;",
                "    ",
                "    @Enumerated(EnumType.STRING)",
                "    @Column(name = \"status\")",
                "    private OrderStatus status;",
                "    ",
                "    public enum OrderStatus {",
                "        PENDING, CONFIRMED, SHIPPED, DELIVERED",
                "    }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // Enumerated가 있는 컬럼이 처리되었는지 확인
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("status")));
        });
    }

    @Test
    @DisplayName("Temporal 어노테이션의 FQCN 조회가 정상 동작한다")
    void temporalAnnotation_fqcnLookup() {
        JavaFileObject source = forSourceLines(
                "test.Event",
                "package test;",
                "import jakarta.persistence.*;",
                "import java.util.Date;",
                "",
                "@Entity",
                "public class Event {",
                "    @Id",
                "    private Long id;",
                "    ",
                "    @Temporal(TemporalType.TIMESTAMP)",
                "    @Column(name = \"event_time\")",
                "    private Date eventTime;",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // Temporal이 있는 컬럼이 처리되었는지 확인
            assertTrue(entity.getColumns().values().stream()
                    .anyMatch(c -> c.getColumnName().equals("event_time")));
        });
    }
}
