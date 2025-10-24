package org.jinx.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MigrateCommand 기본 테스트
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
    @DisplayName("스키마 파일이 없으면 'No HEAD schema found' 메시지 출력")
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
    @DisplayName("help 옵션 테스트")
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
    @DisplayName("지원하지 않는 dialect 사용 시 에러")
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

            // then - 실패해야 함
            assertThat(exitCode).isEqualTo(1);
            // "Unsupported dialect" 또는 "Migration failed" 메시지가 있어야 함
            String errorOutput = errContent.toString();
            assertThat(errorOutput).containsAnyOf("Unsupported dialect", "Migration failed", "oracle");
        } finally {
            tearDown();
        }
    }
}
