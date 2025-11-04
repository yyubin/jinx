package org.jinx.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests for MigrateCommand.
 */
class MigrateCommandTest {

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
    @DisplayName("Displays 'No HEAD schema found' message when no schema file")
    void testNoSchemaFile() {
        try {
            int exitCode = new CommandLine(new MigrateCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString());

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("No HEAD schema found");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Help option displays migration description")
    void testMigrateHelp() {
        try {
            ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
            ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
            PrintWriter pwOut = new PrintWriter(helpOut);
            PrintWriter pwErr = new PrintWriter(helpErr);

            int exitCode = new CommandLine(new MigrateCommand())
                    .setOut(pwOut)
                    .setErr(pwErr)
                    .execute("--help");
            pwOut.flush();
            pwErr.flush();

            assertThat(exitCode).isZero();
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).contains("마이그레이션");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Returns error for unsupported dialect")
    void testUnsupportedDialect() throws IOException {
        // given
        createSchemaFile("20240101000000", """
                {
                  "version":"20240101000000",
                  "entities":{
                    "Test":{
                      "tableName":"test_table",
                      "columns":{
                        "id":{"columnName":"id","sqlType":"BIGINT","nullable":false}
                      },
                      "primaryKey":{"columns":["id"]},
                      "indexes":{},
                      "uniqueConstraints":{}
                    }
                  }
                }
                """);

        Files.createDirectories(outputDir);

        try {
            // when
            int exitCode = new CommandLine(new MigrateCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString(),
                            "--dialect", "oracle");

            // then - Should fail
            assertThat(exitCode).isEqualTo(1);
            // Should contain "Unsupported dialect" or "Migration failed" message
            String errorOutput = errContent.toString();
            assertThat(errorOutput).containsAnyOf("Unsupported dialect", "Migration failed", "oracle");
        } finally {
            tearDown();
        }
    }
}
