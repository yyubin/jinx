package org.jinx.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.jinx.config.ConfigurationLoader;
import org.jinx.options.JinxOptions;

import java.io.File;
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

    @Override
    public void apply(Project project) {
        // 1. Create jinx extension
        JinxExtension extension = project.getExtensions().create(EXTENSION_NAME, JinxExtension.class);

        // 2. Configure JavaCompile tasks after project evaluation
        project.afterEvaluate(this::configureJavaCompileTasks);

        // 3. Create CLI tasks
        createCliTasks(project, extension);
    }

    private void configureJavaCompileTasks(Project project) {
        JinxExtension extension = project.getExtensions().getByType(JinxExtension.class);

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            // Load configuration with priority: Gradle DSL > YAML file > defaults
            Map<String, String> resolvedConfig = resolveConfiguration(project, extension);

            // Apply configuration as -A options
            resolvedConfig.forEach((key, value) -> {
                task.getOptions().getCompilerArgs().add("-A" + key + "=" + value);
            });

            project.getLogger().info("Applied Jinx configuration to {}: {}", task.getName(), resolvedConfig);
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

    private void createCliTasks(Project project, JinxExtension extension) {
        // Create jinxMigrate task
        project.getTasks().register("jinxMigrate", JavaExec.class, task -> {
            task.setGroup("jinx");
            task.setDescription("Generate database migration files");

            // Configure main class and classpath
            task.getMainClass().set("org.jinx.cli.JinxCli");

            // Set classpath after evaluation to ensure configurations are available
            project.afterEvaluate(p -> {
                if (project.getConfigurations().findByName("runtimeClasspath") != null) {
                    task.setClasspath(project.getConfigurations().getByName("runtimeClasspath"));
                }
            });

            // Set default arguments
            task.args("db", "migrate");

            // Apply configuration
            project.afterEvaluate(p -> {
                // Add profile argument if specified
                if (extension.getProfile().isPresent()) {
                    task.args("--profile", extension.getProfile().get());
                }

                // Add other CLI arguments based on extension configuration
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
        });
    }
}