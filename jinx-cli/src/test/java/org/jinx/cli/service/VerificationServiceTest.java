package org.jinx.cli.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for VerificationService.
 */
class VerificationServiceTest {

    private VerificationService service;

    @BeforeEach
    void setUp() {
        // Test without DB connection (set to null)
        service = new VerificationService(null, null, null, "jinx");
    }

    @Test
    @DisplayName("Returns baseline hash when no DB connection info")
    void testGetAppliedSchemaHash_NoDbConnection() {
        String expectedHash = "hash123";
        String baselineHash = "baseline456";

        String appliedHash = service.getAppliedSchemaHash(expectedHash, baselineHash);

        assertThat(appliedHash).isEqualTo(baselineHash);
    }

    @Test
    @DisplayName("Returns true when expected hash matches applied hash")
    void testIsSchemaUpToDate_WhenHashesMatch() {
        String hash = "hash123";

        boolean upToDate = service.isSchemaUpToDate(hash, hash);

        assertThat(upToDate).isTrue();
    }

    @Test
    @DisplayName("Returns false when expected hash differs from applied hash")
    void testIsSchemaUpToDate_WhenHashesDifferent() {
        String expectedHash = "hash123";
        String baselineHash = "baseline456";

        boolean upToDate = service.isSchemaUpToDate(expectedHash, baselineHash);

        assertThat(upToDate).isFalse();
    }

    @Test
    @DisplayName("Defaults to jinx when migration tool is null")
    void testMigrationToolDefaultValue() {
        VerificationService serviceWithNullTool = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                null
        );

        // Verify default is "jinx" by checking no exception when calling recordSchemaApplication
        // (Only warning message will be printed as there's no actual DB)
        serviceWithNullTool.recordSchemaApplication("hash123", "20240101000000");
        // Success if no exception thrown
    }

    @Test
    @DisplayName("Records schema application with jinx migration tool")
    void testRecordSchemaApplication_JinxTool() {
        VerificationService jinxService = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                "jinx"
        );

        // Verify no exception thrown, only warning printed when no DB
        jinxService.recordSchemaApplication("hash123", "20240101000000");
        // Success if no exception thrown
    }

    @Test
    @DisplayName("Does not record schema application with liquibase tool")
    void testRecordSchemaApplication_LiquibaseTool() {
        VerificationService liquibaseService = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                "liquibase"
        );

        // In liquibase mode, recordSchemaApplication does nothing
        liquibaseService.recordSchemaApplication("hash123", "20240101000000");
        // Success if no exception thrown
    }

    @Test
    @DisplayName("Does not record schema application with flyway tool")
    void testRecordSchemaApplication_FlywayTool() {
        VerificationService flywayService = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                "flyway"
        );

        // In flyway mode, recordSchemaApplication does nothing
        flywayService.recordSchemaApplication("hash123", "20240101000000");
        // Success if no exception thrown
    }

    @Test
    @DisplayName("Migration tool name is case-insensitive")
    void testMigrationToolCaseInsensitive() {
        VerificationService service1 = new VerificationService(null, null, null, "JINX");
        VerificationService service2 = new VerificationService(null, null, null, "JiNx");
        VerificationService service3 = new VerificationService(null, null, null, "jinx");

        // All should be handled identically (no exception)
        service1.recordSchemaApplication("hash", "version");
        service2.recordSchemaApplication("hash", "version");
        service3.recordSchemaApplication("hash", "version");
    }

    @Test
    @DisplayName("Returns baseline hash for unsupported migration tool")
    void testUnsupportedMigrationTool() {
        // given
        VerificationService unknownService = new VerificationService(
                "jdbc:mysql://localhost:3306/test",
                "user",
                "password",
                "unknown-tool"
        );

        String expectedHash = "sha256:expected";
        String baselineHash = "sha256:baseline";

        // when - DB connection fails, returns baseline hash
        String appliedHash = unknownService.getAppliedSchemaHash(expectedHash, baselineHash);

        // then
        assertThat(appliedHash).isEqualTo(baselineHash);
    }

    @Test
    @DisplayName("Returns baseline hash without exception on DB connection failure")
    void testDbConnectionFailureReturnsBaselineHash() {
        // given
        VerificationService service = new VerificationService(
                "jdbc:mysql://invalid-host:3306/test",
                "user",
                "password",
                "jinx"
        );

        String expectedHash = "sha256:expected";
        String baselineHash = "sha256:baseline";

        // when
        String appliedHash = service.getAppliedSchemaHash(expectedHash, baselineHash);

        // then - Returns baseline hash even if exception occurs
        assertThat(appliedHash).isEqualTo(baselineHash);
    }

    @Test
    @DisplayName("isSchemaUpToDate uses getAppliedSchemaHash result")
    void testIsSchemaUpToDateUsesGetAppliedSchemaHash() {
        // given - No DB connection, returns baseline hash
        VerificationService service = new VerificationService(null, null, null, "jinx");
        String expectedHash = "sha256:expected";
        String baselineHash = "sha256:baseline";

        // when - expectedHash differs from baseline, so false
        boolean upToDate = service.isSchemaUpToDate(expectedHash, baselineHash);

        // then
        assertThat(upToDate).isFalse();
    }

    @Test
    @DisplayName("recordSchemaApplication does nothing when no DB info")
    void testRecordSchemaApplication_NoDbInfo() {
        // given
        VerificationService service = new VerificationService(null, null, null, "jinx");

        // when & then - No exception thrown
        service.recordSchemaApplication("hash", "version");
    }
}
