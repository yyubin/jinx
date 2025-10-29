package org.jinx.migration.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.model.ColumnKey;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BaselineManagerTest {

    @TempDir
    Path tempDir;

    private BaselineManager baselineManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        baselineManager = new BaselineManager(tempDir);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("동일한 스키마는 동일한 해시를 생성한다")
    void generateSchemaHash_sameSchemaProducesSameHash() {
        SchemaModel schema1 = createTestSchema("v1");
        SchemaModel schema2 = createTestSchema("v1");

        String hash1 = baselineManager.generateSchemaHash(schema1);
        String hash2 = baselineManager.generateSchemaHash(schema2);

        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("다른 스키마는 다른 해시를 생성한다")
    void generateSchemaHash_differentSchemaProducesDifferentHash() {
        SchemaModel schema1 = createTestSchema("v1");
        SchemaModel schema2 = createTestSchema("v2");

        String hash1 = baselineManager.generateSchemaHash(schema1);
        String hash2 = baselineManager.generateSchemaHash(schema2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("생성된 해시는 SHA-256 형식이다 (64자 16진수)")
    void generateSchemaHash_producesValidSha256() {
        SchemaModel schema = createTestSchema("v1");

        String hash = baselineManager.generateSchemaHash(schema);

        assertNotNull(hash);
        assertEquals(64, hash.length()); // SHA-256은 64자의 16진수 문자열
        assertTrue(hash.matches("[0-9a-f]{64}")); // 소문자 16진수만 포함
    }

    @Test
    @DisplayName("해시 생성은 일관성이 있다 (여러 번 생성해도 동일)")
    void generateSchemaHash_isConsistent() {
        SchemaModel schema = createTestSchema("v1");

        String hash1 = baselineManager.generateSchemaHash(schema);
        String hash2 = baselineManager.generateSchemaHash(schema);
        String hash3 = baselineManager.generateSchemaHash(schema);

        assertEquals(hash1, hash2);
        assertEquals(hash2, hash3);
    }

    @Test
    @DisplayName("베이스라인이 없을 때 loadBaseline은 초기 베이스라인을 반환한다")
    void loadBaseline_returnsInitialBaselineWhenNoBaselineExists() throws IOException {
        SchemaModel baseline = baselineManager.loadBaseline();

        assertNotNull(baseline);
        assertEquals("initial", baseline.getVersion());
        assertTrue(baseline.getEntities().isEmpty());
        assertTrue(baseline.getSequences().isEmpty());
    }

    @Test
    @DisplayName("베이스라인이 있을 때 loadBaseline은 저장된 베이스라인을 로드한다")
    void loadBaseline_loadsExistingBaseline() throws IOException {
        // Given: 베이스라인 생성 및 저장
        SchemaModel originalSchema = createTestSchema("v1.0");
        String hash = baselineManager.generateSchemaHash(originalSchema);
        baselineManager.promoteBaseline(originalSchema, hash);

        // When: 베이스라인 로드
        SchemaModel loadedBaseline = baselineManager.loadBaseline();

        // Then: 원본 스키마와 동일
        assertNotNull(loadedBaseline);
        assertEquals("v1.0", loadedBaseline.getVersion());
        assertFalse(loadedBaseline.getEntities().isEmpty());
    }

    @Test
    @DisplayName("hasBaseline은 베이스라인이 없을 때 false를 반환한다")
    void hasBaseline_returnsFalseWhenNoBaseline() {
        assertFalse(baselineManager.hasBaseline());
    }

    @Test
    @DisplayName("hasBaseline은 베이스라인이 있을 때 true를 반환한다")
    void hasBaseline_returnsTrueWhenBaselineExists() throws IOException {
        // Given: 베이스라인 생성
        SchemaModel schema = createTestSchema("v1.0");
        String hash = baselineManager.generateSchemaHash(schema);
        baselineManager.promoteBaseline(schema, hash);

        // When & Then
        assertTrue(baselineManager.hasBaseline());
    }

    @Test
    @DisplayName("getBaselineHash는 메타데이터가 없을 때 empty를 반환한다")
    void getBaselineHash_returnsEmptyWhenNoMetadata() {
        assertTrue(baselineManager.getBaselineHash().isEmpty());
    }

    @Test
    @DisplayName("getBaselineHash는 메타데이터가 있을 때 해시를 반환한다")
    void getBaselineHash_returnsHashWhenMetadataExists() throws IOException {
        // Given: 베이스라인 승격
        SchemaModel schema = createTestSchema("v1.0");
        String originalHash = baselineManager.generateSchemaHash(schema);
        baselineManager.promoteBaseline(schema, originalHash);

        // When: 해시 조회
        var retrievedHash = baselineManager.getBaselineHash();

        // Then
        assertTrue(retrievedHash.isPresent());
        assertEquals(originalHash, retrievedHash.get());
    }

    @Test
    @DisplayName("promoteBaseline은 베이스라인과 메타데이터를 저장한다")
    void promoteBaseline_savesBaselineAndMetadata() throws IOException {
        // Given
        SchemaModel schema = createTestSchema("v2.0");
        String hash = baselineManager.generateSchemaHash(schema);

        // When: 베이스라인 승격
        baselineManager.promoteBaseline(schema, hash);

        // Then: 파일들이 생성되었는지 확인
        Path baselineFile = tempDir.resolve("schema-baseline.json");
        Path metadataFile = tempDir.resolve("baseline-metadata.json");

        assertTrue(Files.exists(baselineFile));
        assertTrue(Files.exists(metadataFile));

        // 베이스라인 파일 검증
        SchemaModel loadedSchema = objectMapper.readValue(baselineFile.toFile(), SchemaModel.class);
        assertEquals("v2.0", loadedSchema.getVersion());

        // 메타데이터 파일 검증
        BaselineManager.BaselineMetadata metadata = objectMapper.readValue(
                metadataFile.toFile(),
                BaselineManager.BaselineMetadata.class
        );
        assertEquals(hash, metadata.getSchemaHash());
        assertEquals("v2.0", metadata.getVersion());
        assertNotNull(metadata.getPromotedAt());
    }

    @Test
    @DisplayName("promoteBaseline은 디렉토리가 없을 때 디렉토리를 생성한다")
    void promoteBaseline_createsDirectoryIfNotExists() throws IOException {
        // Given: 존재하지 않는 디렉토리
        Path nonExistentDir = tempDir.resolve("nested/dir/path");
        BaselineManager managerWithNonExistentDir = new BaselineManager(nonExistentDir);
        SchemaModel schema = createTestSchema("v1.0");
        String hash = managerWithNonExistentDir.generateSchemaHash(schema);

        // When
        managerWithNonExistentDir.promoteBaseline(schema, hash);

        // Then: 디렉토리와 파일들이 생성됨
        assertTrue(Files.exists(nonExistentDir));
        assertTrue(Files.exists(nonExistentDir.resolve("schema-baseline.json")));
        assertTrue(Files.exists(nonExistentDir.resolve("baseline-metadata.json")));
    }

    @Test
    @DisplayName("엔티티가 추가되면 해시가 변경된다")
    void schemaHash_changesWhenEntityAdded() {
        SchemaModel schema1 = SchemaModel.builder()
                .version("v1")
                .entities(new HashMap<>())
                .build();

        SchemaModel schema2 = SchemaModel.builder()
                .version("v1")
                .entities(Map.of("User", createTestEntity("User")))
                .build();

        String hash1 = baselineManager.generateSchemaHash(schema1);
        String hash2 = baselineManager.generateSchemaHash(schema2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("컬럼이 변경되면 해시가 변경된다")
    void schemaHash_changesWhenColumnModified() {
        EntityModel entity1 = createTestEntity("User");
        entity1.getColumns().put(ColumnKey.of("User", "id"), ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .build());

        EntityModel entity2 = createTestEntity("User");
        entity2.getColumns().put(ColumnKey.of("User", "id"), ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.String") // 타입 변경
                .build());

        SchemaModel schema1 = SchemaModel.builder()
                .version("v1")
                .entities(Map.of("User", entity1))
                .build();

        SchemaModel schema2 = SchemaModel.builder()
                .version("v1")
                .entities(Map.of("User", entity2))
                .build();

        String hash1 = baselineManager.generateSchemaHash(schema1);
        String hash2 = baselineManager.generateSchemaHash(schema2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("메타데이터에서 빈 해시는 empty로 반환된다")
    void getBaselineHash_returnsEmptyForBlankHash() throws IOException {
        // Given: 빈 해시를 가진 메타데이터
        BaselineManager.BaselineMetadata metadata = BaselineManager.BaselineMetadata.builder()
                .schemaHash("")
                .version("v1")
                .promotedAt("2024-01-01T00:00:00")
                .build();

        Path metadataFile = tempDir.resolve("baseline-metadata.json");
        objectMapper.writeValue(metadataFile.toFile(), metadata);

        // When
        var hash = baselineManager.getBaselineHash();

        // Then
        assertTrue(hash.isEmpty());
    }

    @Test
    @DisplayName("메타데이터 파일이 손상되었을 때 getBaselineHash는 empty를 반환한다")
    void getBaselineHash_returnsEmptyForCorruptedMetadata() throws IOException {
        // Given: 손상된 메타데이터 파일
        Path metadataFile = tempDir.resolve("baseline-metadata.json");
        Files.writeString(metadataFile, "{ invalid json ::::");

        // When
        var hash = baselineManager.getBaselineHash();

        // Then: 예외 대신 empty 반환
        assertTrue(hash.isEmpty());
    }

    @Test
    @DisplayName("BaselineMetadata 빌더는 올바르게 동작한다")
    void baselineMetadata_builderWorks() {
        String expectedHash = "abc123";
        String expectedVersion = "v1.0.0";
        String expectedPromotedAt = "2024-01-01T12:00:00";

        BaselineManager.BaselineMetadata metadata = BaselineManager.BaselineMetadata.builder()
                .schemaHash(expectedHash)
                .version(expectedVersion)
                .promotedAt(expectedPromotedAt)
                .build();

        assertEquals(expectedHash, metadata.getSchemaHash());
        assertEquals(expectedVersion, metadata.getVersion());
        assertEquals(expectedPromotedAt, metadata.getPromotedAt());
    }

    @Test
    @DisplayName("BaselineMetadata getter/setter는 올바르게 동작한다")
    void baselineMetadata_gettersAndSetters() {
        BaselineManager.BaselineMetadata metadata = new BaselineManager.BaselineMetadata();

        metadata.setSchemaHash("hash123");
        metadata.setVersion("v2.0");
        metadata.setPromotedAt("2024-12-31T23:59:59");

        assertEquals("hash123", metadata.getSchemaHash());
        assertEquals("v2.0", metadata.getVersion());
        assertEquals("2024-12-31T23:59:59", metadata.getPromotedAt());
    }

    // Helper methods

    private SchemaModel createTestSchema(String version) {
        Map<String, EntityModel> entities = new HashMap<>();
        entities.put("User", createTestEntity("User"));

        return SchemaModel.builder()
                .version(version)
                .entities(entities)
                .build();
    }

    private EntityModel createTestEntity(String tableName) {
        EntityModel entity = EntityModel.builder()
                .tableName(tableName)
                .entityName(tableName)
                .build();

        // 테스트를 위한 기본 컬럼 추가
        entity.getColumns().put(ColumnKey.of(tableName, "id"), ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build());

        return entity;
    }
}
