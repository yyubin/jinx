package org.jinx.descriptor;

import com.google.testing.compile.Compilation;
import org.jinx.processor.AbstractProcessorTest;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AttributeDescriptorFactory 테스트
 *
 * AttributeDescriptorFactory의 다양한 시나리오를 검증합니다.
 * - 기본 AccessType 결정 로직
 * - 명시적 @Access 어노테이션 처리
 * - 충돌 감지 및 에러 처리
 */
class AttributeDescriptorFactoryTest extends AbstractProcessorTest {

    @Test
    void testDefaultFieldAccess() {
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
                "    private String name;",
                "    private Integer age;",
                "    ",
                "    // Getters/setters는 있지만 어노테이션이 없으므로 필드 접근이 우선",
                "    public Long getId() { return id; }",
                "    public String getName() { return name; }",
                "    public Integer getAge() { return age; }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            assertEquals(1, schema.getEntities().size());
            var entity = schema.getEntities().values().iterator().next();

            // 필드 접근으로 처리되어야 함
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("name")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("age")));
        });
    }

    @Test
    void testExplicitPropertyAccess() {
        JavaFileObject source = forSourceLines(
                "test.User",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "@Access(AccessType.PROPERTY)",
                "public class User {",
                "    private Long id;",
                "    private String name;",
                "    ",
                "    @Id",
                "    public Long getId() { return id; }",
                "    ",
                "    @Column(name = \"user_name\")",
                "    public String getName() { return name; }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            assertEquals(1, schema.getEntities().size());
            var entity = schema.getEntities().values().iterator().next();

            // 프로퍼티 접근으로 처리되어야 함
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("user_name")));
        });
    }

    @Test
    void testConflictingAccessAnnotations() {
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
                "    @Access(AccessType.FIELD)",
                "    @Column(name = \"field_name\")",
                "    private String name;",
                "    ",
                "    @Access(AccessType.PROPERTY)",
                "    @Column(name = \"property_name\")",
                "    public String getName() { return name; }",
                "}"
        );

        Compilation compilation = compile(source);
        // 충돌하는 @Access 어노테이션이 있으면 에러 발생
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Conflicting @Access annotations");
    }

    @Test
    void testConflictingMappingAnnotations() {
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
                "    @Column(name = \"field_name\")",
                "    private String name;",
                "    ",
                "    @Column(name = \"property_name\")",
                "    public String getName() { return name; }",
                "}"
        );

        Compilation compilation = compile(source);
        // 충돌하는 매핑 어노테이션이 있으면 에러 발생
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Conflicting JPA mapping annotations");
    }

    @Test
    void testInheritanceWithMappedSuperclass() {
        JavaFileObject baseEntity = forSourceLines(
                "test.BaseEntity",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@MappedSuperclass",
                "public abstract class BaseEntity {",
                "    @Id",
                "    @GeneratedValue(strategy = GenerationType.IDENTITY)",
                "    private Long id;",
                "    ",
                "    private String createdBy;",
                "}"
        );

        JavaFileObject user = forSourceLines(
                "test.User",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "public class User extends BaseEntity {",
                "    private String name;",
                "    private String email;",
                "}"
        );

        Compilation compilation = compile(baseEntity, user);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // 상속받은 필드들과 자체 필드들이 모두 처리되어야 함
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("createdBy")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("name")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("email")));
        });
    }

    @Test
    void testFieldAndPropertyWithSameName() {
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
                "    // 필드에만 어노테이션 -> 필드 접근",
                "    @Column(name = \"user_name\")",
                "    private String name;",
                "    ",
                "    public String getName() { return name; }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // 필드에 매핑 어노테이션이 있으므로 필드 접근 우선
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("user_name")));
        });
    }

    @Test
    void testPropertyHasMappingAnnotation() {
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
                "    private String name;",
                "    ",
                "    // getter에 매핑 어노테이션 -> 프로퍼티 접근",
                "    @Column(name = \"user_name\")",
                "    public String getName() { return name; }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // getter에 매핑 어노테이션이 있으므로 프로퍼티 접근
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("user_name")));
        });
    }
}
