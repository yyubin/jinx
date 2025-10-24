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
 * VerifyCommand 테스트
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
    @DisplayName("스키마가 없으면 에러 반환")
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
    @DisplayName("baseline이 없고 DB 연결 정보도 없으면 초기 상태로 간주")
    void testVerify_NoBaselineNoDb() throws IOException {
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        try {
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("-p", schemaDir.toString(), "--out", outputDir.toString());

            // DB 연결 정보가 없으므로 baseline (initial)과 비교
            // 최신 스키마 해시 != "initial" 이므로 mismatch
            assertThat(exitCode).isEqualTo(1);
            assertThat(outContent.toString()).contains("Schema mismatch detected");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("baseline과 최신 스키마가 동일하면 up-to-date")
    void testVerify_UpToDate() throws IOException {
        // 스키마 생성
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        // baseline 생성 (promote-baseline 시뮬레이션)
        int promoteCode = new CommandLine(new PromoteBaselineCommand())
                .execute("-p", schemaDir.toString(),
                        "--out", outputDir.toString(),
                        "--force");

        assertThat(promoteCode).isZero();

        try {
            // verify 실행
            int exitCode = new CommandLine(new VerifyCommand())
                    .execute("-p", schemaDir.toString(), "--out", outputDir.toString());

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("Schema is up to date");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("스키마가 변경되면 mismatch 감지")
    void testVerify_SchemaMismatch() throws IOException {
        // 초기 스키마 생성 및 baseline 설정
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        new CommandLine(new PromoteBaselineCommand())
                .execute("-p", schemaDir.toString(),
                        "--out", outputDir.toString(),
                        "--force");

        // 새로운 스키마 생성 (변경됨)
        createSchemaFile("20240102000000", """
                {"version":"20240102000000","entities":{"User":{}}}
                """);

        try {
            // verify 실행
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
    @DisplayName("help 옵션 테스트")
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
