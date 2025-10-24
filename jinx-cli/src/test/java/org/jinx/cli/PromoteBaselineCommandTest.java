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
 * PromoteBaselineCommand 테스트
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
    @DisplayName("스키마가 없으면 에러 반환")
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
    @DisplayName("force 옵션으로 baseline 승격")
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

            // baseline 파일이 생성되었는지 확인
            Path baselineFile = outputDir.resolve("schema-baseline.json");
            assertThat(baselineFile).exists();
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("force 없이 검증 성공 시 baseline 승격")
    void testPromoteBaseline_WithVerification() throws IOException {
        // 초기 스키마 생성 및 baseline 설정
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        Files.createDirectories(outputDir);

        // 첫 번째 promote (force)
        new CommandLine(new PromoteBaselineCommand())
                .execute("-p", schemaDir.toString(),
                        "--out", outputDir.toString(),
                        "--force");

        try {
            // 같은 스키마에 대해 force 없이 promote (검증 성공해야 함)
            int exitCode = new CommandLine(new PromoteBaselineCommand())
                    .execute("-p", schemaDir.toString(),
                            "--out", outputDir.toString());

            // DB 연결이 없으므로 baseline과 비교하여 동일하면 성공
            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("Baseline promoted successfully");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("스키마가 변경되었는데 force 없으면 실패")
    void testPromoteBaseline_FailWithoutForce() throws IOException {
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
            // force 없이 promote 시도 (검증 실패해야 함)
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
    @DisplayName("help 옵션 테스트")
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
    @DisplayName("baseline 파일 내용 검증")
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

            // baseline 파일 내용 확인
            Path baselineFile = outputDir.resolve("schema-baseline.json");
            assertThat(baselineFile).exists();

            String baselineContent = Files.readString(baselineFile);
            assertThat(baselineContent).contains("\"version\":\"20240101000000\"");
            assertThat(baselineContent).contains("\"entities\"");

            // metadata 파일도 확인
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
