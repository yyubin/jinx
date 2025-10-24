package org.jinx.descriptor;

import com.google.testing.compile.Compilation;
import org.jinx.processor.AbstractProcessorTest;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PropertyAttributeDescriptor 테스트
 *
 * 프로퍼티(getter/setter) 기반 접근 방식의 속성 디스크립터 동작을 검증합니다.
 */
class PropertyAttributeDescriptorTest extends AbstractProcessorTest {

    @Test
    void testPropertyAccessWithBasicGetters() {
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
                "    private Integer age;",
                "    ",
                "    @Id",
                "    public Long getId() { return id; }",
                "    public void setId(Long id) { this.id = id; }",
                "    ",
                "    @Column(name = \"user_name\")",
                "    public String getName() { return name; }",
                "    public void setName(String name) { this.name = name; }",
                "    ",
                "    public Integer getAge() { return age; }",
                "    public void setAge(Integer age) { this.age = age; }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            assertEquals(1, schema.getEntities().size());
            var entity = schema.getEntities().values().iterator().next();

            // Property access로 컬럼들이 올바르게 처리되었는지 확인
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("user_name")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("age")));
        });
    }

    @Test
    void testPropertyAccessWithBooleanIsGetter() {
        JavaFileObject source = forSourceLines(
                "test.Account",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "@Access(AccessType.PROPERTY)",
                "public class Account {",
                "    private Long id;",
                "    private boolean active;",
                "    private Boolean verified;",
                "    ",
                "    @Id",
                "    public Long getId() { return id; }",
                "    public void setId(Long id) { this.id = id; }",
                "    ",
                "    public boolean isActive() { return active; }",
                "    public void setActive(boolean active) { this.active = active; }",
                "    ",
                "    public Boolean isVerified() { return verified; }",
                "    public void setVerified(Boolean verified) { this.verified = verified; }",
                "}"
        );

        Compilation compilation = compile(source);
        assertCompilationSuccessAndGetSchema(compilation).ifPresent(schema -> {
            assertNotNull(schema);
            var entity = schema.getEntities().values().iterator().next();

            // isXxx getter로부터 올바르게 property name이 추출되었는지 확인
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("active")));
            assertTrue(entity.getColumns().values().stream().anyMatch(c -> c.getColumnName().equals("verified")));
        });
    }

    @Test
    void testPropertyAccessWithEnumType() {
        JavaFileObject source = forSourceLines(
                "test.Product",
                "package test;",
                "import jakarta.persistence.*;",
                "",
                "@Entity",
                "@Access(AccessType.PROPERTY)",
                "public class Product {",
                "    private Long id;",
                "    private Status status;",
                "    ",
                "    @Id",
                "    public Long getId() { return id; }",
                "    public void setId(Long id) { this.id = id; }",
                "    ",
                "    @Enumerated(EnumType.STRING)",
                "    @Column(name = \"status\")",
                "    public Status getStatus() { return status; }",
                "    public void setStatus(Status status) { this.status = status; }",
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
}
