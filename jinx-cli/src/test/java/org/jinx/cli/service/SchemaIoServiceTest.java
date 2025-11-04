package org.jinx.cli.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SchemaIoService.
 */
class SchemaIoServiceTest {

    @TempDir
    Path tempDir;

    private Path schemaDir;
    private Path outputDir;
    private SchemaIoService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        schemaDir = tempDir.resolve("schemas");
        outputDir = tempDir.resolve("output");
        service = new SchemaIoService(schemaDir, outputDir);
        objectMapper = new ObjectMapper();
    }

    private void createSchemaFile(String timestamp, String content) throws IOException {
        Files.createDirectories(schemaDir);
        Path schemaFile = schemaDir.resolve("schema-" + timestamp + ".json");
        Files.writeString(schemaFile, content);
    }

    @Test
    @DisplayName("Returns null when schema directory does not exist")
    void testLoadLatestSchema_DirectoryNotExists() throws IOException {
        SchemaModel schema = service.loadLatestSchema();
        assertThat(schema).isNull();
    }

    @Test
    @DisplayName("Returns null when no schema files exist")
    void testLoadLatestSchema_NoSchemaFiles() throws IOException {
        Files.createDirectories(schemaDir);
        SchemaModel schema = service.loadLatestSchema();
        assertThat(schema).isNull();
    }

    @Test
    @DisplayName("Loads the most recent schema file")
    void testLoadLatestSchema_LoadsLatest() throws IOException {
        // Create multiple schema files
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        createSchemaFile("20240102000000", """
                {"version":"20240102000000","entities":{}}
                """);

        createSchemaFile("20240103000000", """
                {"version":"20240103000000","entities":{}}
                """);

        SchemaModel schema = service.loadLatestSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getVersion()).isEqualTo("20240103000000");
    }

    @Test
    @DisplayName("Ignores files with invalid format")
    void testLoadLatestSchema_IgnoresInvalidFormat() throws IOException {
        Files.createDirectories(schemaDir);

        // Invalid format files
        Files.writeString(schemaDir.resolve("schema.json"), "{}");
        Files.writeString(schemaDir.resolve("schema-abc.json"), "{}");
        Files.writeString(schemaDir.resolve("other.json"), "{}");

        // Valid format file
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        SchemaModel schema = service.loadLatestSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getVersion()).isEqualTo("20240101000000");
    }

    @Test
    @DisplayName("Generates schema hash")
    void testGenerateSchemaHash() {
        SchemaModel schema = SchemaModel.builder()
                .version("20240101000000")
                .build();

        String hash = service.generateSchemaHash(schema);

        assertThat(hash).isNotNull();
        assertThat(hash).isNotEmpty();
    }

    @Test
    @DisplayName("Generates same hash for identical schemas")
    void testGenerateSchemaHash_SameSchemasSameHash() {
        SchemaModel schema1 = SchemaModel.builder()
                .version("20240101000000")
                .build();

        SchemaModel schema2 = SchemaModel.builder()
                .version("20240101000000")
                .build();

        String hash1 = service.generateSchemaHash(schema1);
        String hash2 = service.generateSchemaHash(schema2);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Returns 'initial' when no baseline exists")
    void testGetBaselineHash_NoBaseline() {
        String hash = service.getBaselineHash();
        assertThat(hash).isEqualTo("initial");
    }

    @Test
    @DisplayName("Promotes schema to baseline")
    void testPromoteToBaseline() throws IOException {
        Files.createDirectories(outputDir);

        SchemaModel schema = SchemaModel.builder()
                .version("20240101000000")
                .build();

        String hash = service.generateSchemaHash(schema);
        service.promoteToBaseline(schema, hash);

        // Verify baseline file is created
        Path baselineFile = outputDir.resolve("schema-baseline.json");
        assertThat(baselineFile).exists();

        // Verify baseline hash is correct
        String baselineHash = service.getBaselineHash();
        assertThat(baselineHash).isEqualTo(hash);
    }

    @Test
    @DisplayName("Tests schema filename pattern with current timestamp")
    void testSchemaFilePattern() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        createSchemaFile(timestamp, """
                {"version":"%s","entities":{}}
                """.formatted(timestamp));

        SchemaModel schema = service.loadLatestSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getVersion()).isEqualTo(timestamp);
    }
}
