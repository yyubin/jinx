package org.jinx.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PromoteBaselineCommand.
 */
class PromoteBaselineCommandTest {

    @TempDir
    Path tempDir;

    private Path schemaDir;
    private Path outputDir;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        schemaDir = tempDir.resolve("schemas");
        outputDir = tempDir.resolve("output");

        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void createSchemaFile(String timestamp, String content) throws IOException {
        Files.createDirectories(schemaDir);
        Path schemaFile = schemaDir.resolve("schema-" + timestamp + ".json");
        Files.writeString(schemaFile, content);
    }

    @Test
    @DisplayName("Returns error when no schema exists")
    void testPromoteBaseline_NoSchema() {
        try {
            int exitCode = new CommandLine(new PromoteBaselineCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString(),
                            "--force");

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("No HEAD schema found");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Promotes baseline with force option")
    void testPromoteBaseline_Force() throws IOException {
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        try {
            int exitCode = new CommandLine(new PromoteBaselineCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString(),
                            "--force");

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("Baseline promoted successfully");
            assertThat(outContent.toString()).contains("Version: 20240101000000");

            // Verify baseline file is created
            Path baselineFile = outputDir.resolve("schema-baseline.json");
            assertThat(baselineFile).exists();
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Promotes baseline after successful verification without force")
    void testPromoteBaseline_WithVerification() throws IOException {
        // Create initial schema and set baseline
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        // First promote (force)
        new CommandLine(new PromoteBaselineCommand())
                .execute("-p", schemaDir.toString(),
                        "--out", outputDir.toString(),
                        "--force");

        try {
            // Promote without force for same schema (should succeed verification)
            int exitCode = new CommandLine(new PromoteBaselineCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString());

            // No DB connection, succeeds if identical to baseline
            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("Baseline promoted successfully");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Fails without force when schema changes")
    void testPromoteBaseline_FailWithoutForce() throws IOException {
        // Create initial schema and set baseline
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        new CommandLine(new PromoteBaselineCommand())
                .execute("-p", schemaDir.toString(),
                        "--out", outputDir.toString(),
                        "--force");

        // Create new schema (changed)
        createSchemaFile("20240102000000", """
                {"version":"20240102000000","entities":{"User":{}}}
                """);

        try {
            // Attempt promote without force (should fail verification)
            int exitCode = new CommandLine(new PromoteBaselineCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString());

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("Cannot promote baseline - schema verification failed");
            assertThat(errContent.toString()).contains("Use --force to promote anyway");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Help option displays promote baseline description")
    void testPromoteBaseline_Help() {
        try {
            int exitCode = new CommandLine(new PromoteBaselineCommand())
                    .execute("--help");

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("현재 HEAD 스키마를 baseline으로 승격");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Verifies baseline file content")
    void testPromoteBaseline_BaselineFileContent() throws IOException {
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        try {
            new CommandLine(new PromoteBaselineCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString(),
                            "--force");

            // Verify baseline file content
            Path baselineFile = outputDir.resolve("schema-baseline.json");
            assertThat(baselineFile).exists();

            String baselineContent = Files.readString(baselineFile);
            assertThat(baselineContent).contains("\"version\":\"20240101000000\"");
            assertThat(baselineContent).contains("\"entities\"");

            // Also verify metadata file
            Path metadataFile = outputDir.resolve("baseline-metadata.json");
            if (Files.exists(metadataFile)) {
                String metadataContent = Files.readString(metadataFile);
                assertThat(metadataContent).isNotEmpty();
            }
        } finally {
            tearDown();
        }
    }
}
