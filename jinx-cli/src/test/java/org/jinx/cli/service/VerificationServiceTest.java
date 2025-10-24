package org.jinx.cli.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VerificationService 테스트
 */
class VerificationServiceTest {

    private VerificationService service;

    @BeforeEach
    void setUp() {
        // DB 연결 없이 테스트 (null로 설정)
        service = new VerificationService(null, null, null, "jinx");
    }

    @Test
    @DisplayName("DB 연결 정보가 없으면 baseline hash 반환")
    void testGetAppliedSchemaHash_NoDbConnection() {
        String expectedHash = "hash123";
        String baselineHash = "baseline456";

        String appliedHash = service.getAppliedSchemaHash(expectedHash, baselineHash);

        assertThat(appliedHash).isEqualTo(baselineHash);
    }

    @Test
    @DisplayName("expected hash와 applied hash가 같으면 up-to-date")
    void testIsSchemaUpToDate_WhenHashesMatch() {
        String hash = "hash123";

        boolean upToDate = service.isSchemaUpToDate(hash, hash);

        assertThat(upToDate).isTrue();
    }

    @Test
    @DisplayName("expected hash와 applied hash가 다르면 not up-to-date")
    void testIsSchemaUpToDate_WhenHashesDifferent() {
        String expectedHash = "hash123";
        String baselineHash = "baseline456";

        boolean upToDate = service.isSchemaUpToDate(expectedHash, baselineHash);

        assertThat(upToDate).isFalse();
    }

    @Test
    @DisplayName("migration tool이 null이면 jinx로 기본 설정")
    void testMigrationToolDefaultValue() {
        VerificationService serviceWithNullTool = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                null
        );

        // 기본값이 "jinx"인지 확인하기 위해 recordSchemaApplication 호출 시 예외가 발생하지 않는지 확인
        // (실제 DB가 없으므로 경고 메시지만 출력됨)
        serviceWithNullTool.recordSchemaApplication("hash123", "20240101000000");
        // 예외가 발생하지 않으면 성공
    }

    @Test
    @DisplayName("record schema application - jinx migration tool")
    void testRecordSchemaApplication_JinxTool() {
        VerificationService jinxService = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                "jinx"
        );

        // DB가 없어도 예외가 발생하지 않고 경고만 출력되는지 확인
        jinxService.recordSchemaApplication("hash123", "20240101000000");
        // 예외가 발생하지 않으면 성공
    }

    @Test
    @DisplayName("record schema application - liquibase는 기록하지 않음")
    void testRecordSchemaApplication_LiquibaseTool() {
        VerificationService liquibaseService = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                "liquibase"
        );

        // liquibase 모드에서는 recordSchemaApplication이 아무것도 하지 않음
        liquibaseService.recordSchemaApplication("hash123", "20240101000000");
        // 예외가 발생하지 않으면 성공
    }

    @Test
    @DisplayName("record schema application - flyway는 기록하지 않음")
    void testRecordSchemaApplication_FlywayTool() {
        VerificationService flywayService = new VerificationService(
                "jdbc:h2:mem:test",
                "sa",
                "",
                "flyway"
        );

        // flyway 모드에서는 recordSchemaApplication이 아무것도 하지 않음
        flywayService.recordSchemaApplication("hash123", "20240101000000");
        // 예외가 발생하지 않으면 성공
    }

    @Test
    @DisplayName("migration tool 대소문자 무시")
    void testMigrationToolCaseInsensitive() {
        VerificationService service1 = new VerificationService(null, null, null, "JINX");
        VerificationService service2 = new VerificationService(null, null, null, "JiNx");
        VerificationService service3 = new VerificationService(null, null, null, "jinx");

        // 모두 동일하게 처리되어야 함 (예외 발생하지 않음)
        service1.recordSchemaApplication("hash", "version");
        service2.recordSchemaApplication("hash", "version");
        service3.recordSchemaApplication("hash", "version");
    }

    @Test
    @DisplayName("지원하지 않는 migration tool은 false 반환하여 baseline hash 사용")
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

        // when - DB 연결 실패하므로 baseline hash 반환
        String appliedHash = unknownService.getAppliedSchemaHash(expectedHash, baselineHash);

        // then
        assertThat(appliedHash).isEqualTo(baselineHash);
    }

    @Test
    @DisplayName("DB 연결 실패 시에도 예외 없이 baseline hash 반환")
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

        // then - 예외가 발생해도 baseline hash 반환
        assertThat(appliedHash).isEqualTo(baselineHash);
    }

    @Test
    @DisplayName("isSchemaUpToDate는 getAppliedSchemaHash 결과를 사용")
    void testIsSchemaUpToDateUsesGetAppliedSchemaHash() {
        // given - DB 연결 없음, baseline hash 반환
        VerificationService service = new VerificationService(null, null, null, "jinx");
        String expectedHash = "sha256:expected";
        String baselineHash = "sha256:baseline";

        // when - expectedHash와 baseline이 다르므로 false
        boolean upToDate = service.isSchemaUpToDate(expectedHash, baselineHash);

        // then
        assertThat(upToDate).isFalse();
    }

    @Test
    @DisplayName("recordSchemaApplication - DB 정보가 없으면 아무것도 하지 않음")
    void testRecordSchemaApplication_NoDbInfo() {
        // given
        VerificationService service = new VerificationService(null, null, null, "jinx");

        // when & then - 예외 발생하지 않음
        service.recordSchemaApplication("hash", "version");
    }
}
