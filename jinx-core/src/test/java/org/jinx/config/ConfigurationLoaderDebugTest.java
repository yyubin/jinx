package org.jinx.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ConfigurationLoaderDebugTest {

    @Test
    void debugYamlParsing(@TempDir Path tempDir) throws IOException {
        // given
        String yamlContent = """
            profiles:
              dev:
                naming:
                  maxLength: 25
              prod:
                naming:
                  maxLength: 63
            """;

        Path configFile = tempDir.resolve("jinx.yaml");
        Files.writeString(configFile, yamlContent);

        System.out.println("Config file created at: " + configFile);
        System.out.println("Config file exists: " + Files.exists(configFile));
        System.out.println("Config file content:");
        System.out.println(Files.readString(configFile));

        ConfigurationLoader loader = new ConfigurationLoader(tempDir);

        // when
        Map<String, String> config = loader.loadConfiguration("dev");

        // then
        System.out.println("Loaded config: " + config);
    }
}