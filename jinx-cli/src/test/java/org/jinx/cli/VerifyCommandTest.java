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
 * Tests for VerifyCommand.
 */
class VerifyCommandTest {

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
    void testVerify_NoSchema() {
        try {
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("-p", schemaDir.toString(), "--out", outputDir.toString());

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("No HEAD schema found");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Considers as initial state when no baseline and no DB connection")
    void testVerify_NoBaselineNoDb() throws IOException {
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        try {
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("-p", schemaDir.toString(), "--out", outputDir.toString());

            // No DB connection, compare with baseline (initial)
            // Latest schema hash != "initial", so mismatch
            assertThat(exitCode).isEqualTo(1);
            assertThat(outContent.toString()).contains("Schema mismatch detected");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Returns up-to-date when baseline matches latest schema")
    void testVerify_UpToDate() throws IOException {
        // Create schema
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        // Create baseline (simulate promote-baseline)
        int promoteCode = new CommandLine(new PromoteBaselineCommand())
                .execute("-p", schemaDir.toString(),
                        "--out", outputDir.toString(),
                        "--force");

        assertThat(promoteCode).isZero();

        try {
            // Execute verify
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("-p", schemaDir.toString(), "--out", outputDir.toString());

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("Schema is up to date");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Detects mismatch when schema changes")
    void testVerify_SchemaMismatch() throws IOException {
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
            // Execute verify
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("-p", schemaDir.toString(), "--out", outputDir.toString());

            assertThat(exitCode).isEqualTo(1);
            assertThat(outContent.toString()).contains("Schema mismatch detected");
            assertThat(outContent.toString()).contains("Run migration to synchronize");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Help option displays verification description")
    void testVerify_Help() {
        try {
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("--help");

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("데이터베이스에 적용된 스키마와 예상 스키마가 일치하는지 검증");
        } finally {
            tearDown();
        }
    }
}
