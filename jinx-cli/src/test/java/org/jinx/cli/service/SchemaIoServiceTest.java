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
 * SchemaIoService 테스트
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
    @DisplayName("스키마 디렉토리가 없으면 null 반환")
    void testLoadLatestSchema_DirectoryNotExists() throws IOException {
        SchemaModel schema = service.loadLatestSchema();
        assertThat(schema).isNull();
    }

    @Test
    @DisplayName("스키마 파일이 없으면 null 반환")
    void testLoadLatestSchema_NoSchemaFiles() throws IOException {
        Files.createDirectories(schemaDir);
        SchemaModel schema = service.loadLatestSchema();
        assertThat(schema).isNull();
    }

    @Test
    @DisplayName("가장 최신 스키마 파일 로드")
    void testLoadLatestSchema_LoadsLatest() throws IOException {
        // 여러 스키마 파일 생성
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
    @DisplayName("잘못된 형식의 파일은 무시")
    void testLoadLatestSchema_IgnoresInvalidFormat() throws IOException {
        Files.createDirectories(schemaDir);

        // 잘못된 형식의 파일들
        Files.writeString(schemaDir.resolve("schema.json"), "{}");
        Files.writeString(schemaDir.resolve("schema-abc.json"), "{}");
        Files.writeString(schemaDir.resolve("other.json"), "{}");

        // 올바른 형식의 파일
        createSchemaFile("20240101000000", """
                {"version":"20240101000000","entities":{}}
                """);

        SchemaModel schema = service.loadLatestSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getVersion()).isEqualTo("20240101000000");
    }

    @Test
    @DisplayName("스키마 해시 생성")
    void testGenerateSchemaHash() {
        SchemaModel schema = SchemaModel.builder()
                .version("20240101000000")
                .build();

        String hash = service.generateSchemaHash(schema);

        assertThat(hash).isNotNull();
        assertThat(hash).isNotEmpty();
    }

    @Test
    @DisplayName("동일한 스키마는 동일한 해시 생성")
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
    @DisplayName("baseline이 없으면 'initial' 반환")
    void testGetBaselineHash_NoBaseline() {
        String hash = service.getBaselineHash();
        assertThat(hash).isEqualTo("initial");
    }

    @Test
    @DisplayName("baseline 승격")
    void testPromoteToBaseline() throws IOException {
        Files.createDirectories(outputDir);

        SchemaModel schema = SchemaModel.builder()
                .version("20240101000000")
                .build();

        String hash = service.generateSchemaHash(schema);
        service.promoteToBaseline(schema, hash);

        // baseline 파일이 생성되었는지 확인
        Path baselineFile = outputDir.resolve("schema-baseline.json");
        assertThat(baselineFile).exists();

        // baseline hash가 올바른지 확인
        String baselineHash = service.getBaselineHash();
        assertThat(baselineHash).isEqualTo(hash);
    }

    @Test
    @DisplayName("현재 시간 기준으로 스키마 파일명 패턴 테스트")
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
