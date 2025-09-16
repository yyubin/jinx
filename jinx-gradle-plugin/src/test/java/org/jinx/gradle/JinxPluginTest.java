package org.jinx.gradle;

import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JinxPluginTest {

    private Project project;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
    }

    @Test
    @DisplayName("플러그인이 정상적으로 적용된다")
    void plugin_appliesSuccessfully() {
        // when
        project.getPlugins().apply(JinxPlugin.class);

        // then
        assertTrue(project.getExtensions().findByName("jinx") instanceof JinxExtension);
    }

    @Test
    @DisplayName("jinx 확장이 생성되고 설정할 수 있다")
    void jinxExtension_canBeConfigured() {
        // given
        project.getPlugins().apply(JinxPlugin.class);

        // when
        JinxExtension extension = project.getExtensions().getByType(JinxExtension.class);
        extension.getProfile().set("prod");
        extension.getNaming().getMaxLength().set(63);
        extension.getDatabase().getDialect().set("postgresql");

        // then
        assertEquals("prod", extension.getProfile().get());
        assertEquals(63, extension.getNaming().getMaxLength().get());
        assertEquals("postgresql", extension.getDatabase().getDialect().get());
    }

    @Test
    @DisplayName("jinxMigrate 태스크가 등록된다")
    void jinxMigrateTask_isRegistered() {
        // given
        project.getPlugins().apply(JinxPlugin.class);

        // then - 태스크가 등록되었는지만 확인 (실제 생성은 lazy)
        assertNotNull(project.getTasks().named("jinxMigrate"));
    }

    @Test
    @DisplayName("Gradle DSL 설정이 확장에서 동작한다")
    void gradleDsl_worksWithExtension() throws IOException {
        // given
        project.getPlugins().apply(JinxPlugin.class);

        // Create jinx.yaml in project directory
        String yamlContent = """
            profiles:
              dev:
                naming:
                  maxLength: 40
            """;
        Files.writeString(project.getProjectDir().toPath().resolve("jinx.yaml"), yamlContent);

        // when
        JinxExtension extension = project.getExtensions().getByType(JinxExtension.class);
        extension.getProfile().set("dev");
        extension.getNaming().getMaxLength().set(50);

        // then
        assertEquals("dev", extension.getProfile().get());
        assertEquals(50, extension.getNaming().getMaxLength().get());
    }
}