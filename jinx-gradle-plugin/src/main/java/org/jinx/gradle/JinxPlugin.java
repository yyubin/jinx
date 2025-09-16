package org.jinx.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.jinx.config.ConfigurationLoader;
import org.jinx.options.JinxOptions;

import java.util.Map;

/**
 * Gradle plugin for Jinx JPA DDL generation tool
 *
 * This plugin:
 * 1. Creates 'jinx' extension for DSL configuration
 * 2. Loads jinx.yaml configuration file if present
 * 3. Applies configuration to JavaCompile tasks as -A options
 * 4. Creates CLI tasks with proper configuration
 */
public class JinxPlugin implements Plugin<Project> {

    private static final String EXTENSION_NAME = "jinx";
    private static final String CFG_JINX_RUNTIME = "jinxRuntime";

    @Override
    public void apply(Project project) {
        // 1. Create jinx extension
        JinxExtension extension = project.getExtensions().create(EXTENSION_NAME, JinxExtension.class);

        // 2. Create a dedicated configuration for the Jinx CLI tool
        Configuration jinxRuntime = project.getConfigurations().maybeCreate(CFG_JINX_RUNTIME);
        jinxRuntime.setCanBeConsumed(false);
        jinxRuntime.setCanBeResolved(true);
        jinxRuntime.setVisible(false);

        // 3. Add jinx-cli dependency to the configuration
        String pluginVersion = String.valueOf(project.getVersion());
        project.getDependencies().add(CFG_JINX_RUNTIME, "org.jinx:jinx-cli:" + pluginVersion);

        // 4. Configure JavaCompile tasks after project evaluation
        project.afterEvaluate(p -> configureJavaCompileTasks(p, extension));

        // 5. Create CLI tasks
        createCliTasks(project, extension, jinxRuntime);
    }

    private void configureJavaCompileTasks(Project project, JinxExtension extension) {
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            if (!task.getName().equals("compileJava")) {
                return; // Only apply to the main compile task
            }
            // Load configuration with priority: Gradle DSL > YAML file > defaults
            Map<String, String> resolvedConfig = resolveConfiguration(project, extension);

            // Apply configuration as -A options
            resolvedConfig.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    task.getOptions().getCompilerArgs().add("-A" + key + "=" + value);
                }
            });
        });
    }

    private Map<String, String> resolveConfiguration(Project project, JinxExtension extension) {
        // 1. Start with YAML configuration
        String profile = extension.getProfile().getOrNull();
        ConfigurationLoader loader = new ConfigurationLoader(project.getProjectDir().toPath());
        Map<String, String> config = loader.loadConfiguration(profile);

        // 2. Override with Gradle DSL values
        if (extension.getNaming().getMaxLength().isPresent()) {
            config.put(JinxOptions.Naming.MAX_LENGTH_KEY,
                      extension.getNaming().getMaxLength().get().toString());
        }

        // 3. Add profile information
        if (profile != null) {
            config.put(JinxOptions.Profile.PROCESSOR_KEY, profile);
        }

        return config;
    }

    private void createCliTasks(Project project, JinxExtension extension, Configuration jinxRuntime) {
        // Create jinxMigrate task
        project.getTasks().register("jinxMigrate", JavaExec.class, task -> {
            task.setGroup("jinx");
            task.setDescription("Generate database migration files");

            // Configure main class and classpath
            task.getMainClass().set("org.jinx.cli.JinxCli");
            task.setClasspath(jinxRuntime);

            // Set default arguments
            task.args("db", "migrate");

            // Apply configuration from extension after project evaluation
            project.afterEvaluate(p -> {
                if (extension.getProfile().isPresent()) {
                    task.args("--profile", extension.getProfile().get());
                }
                if (extension.getNaming().getMaxLength().isPresent()) {
                    task.args("--max-length", extension.getNaming().getMaxLength().get().toString());
                }
                if (extension.getDatabase().getDialect().isPresent()) {
                    task.args("--dialect", extension.getDatabase().getDialect().get());
                }
                if (extension.getOutput().getDirectory().isPresent()) {
                    task.args("--out", extension.getOutput().getDirectory().get());
                }
            });

            // Add a check to ensure the CLI runtime is available
            task.doFirst(t -> {
                if (jinxRuntime.isEmpty()) {
                    throw new GradleException(
                      "Jinx CLI runtime not resolved. " +
                      "Check plugin version or repository settings for org.jinx:jinx-cli."
                    );
                }
            });
        });
    }
}
