package org.jinx.cli;

import org.jinx.cli.service.SchemaIoService;
import org.jinx.cli.service.VerificationService;
import org.jinx.model.SchemaModel;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "데이터베이스에 적용된 스키마와 예상 스키마가 일치하는지 검증합니다."
)
public class VerifyCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-p", "--path"}, description = "스키마 JSON 파일 폴더", defaultValue = "build/classes/java/main/jinx")
    private Path schemaDir;

    @CommandLine.Option(names = "--out", description = "baseline 파일 위치", defaultValue = "build/jinx")
    private Path outputDir;

    @CommandLine.Option(names = "--db-url", description = "데이터베이스 URL (검증용)")
    private String dbUrl;

    @CommandLine.Option(names = "--db-user", description = "데이터베이스 사용자명")
    private String dbUser;

    @CommandLine.Option(names = "--db-password", description = "데이터베이스 비밀번호")
    private String dbPassword;

    @CommandLine.Option(names = "--migration-tool", description = "사용하는 마이그레이션 도구", defaultValue = "jinx")
    private String migrationTool; // jinx, liquibase, flyway

    @Override
    public Integer call() {
        try {
            // Initialize services
            SchemaIoService schemaIo = new SchemaIoService(schemaDir, outputDir);
            VerificationService verification = new VerificationService(dbUrl, dbUser, dbPassword, migrationTool);

            // 1. 최신 스키마 로드
            SchemaModel latestSchema = schemaIo.loadLatestSchema();
            if (latestSchema == null) {
                System.err.println("No HEAD schema found. Run compilation first.");
                return 1;
            }

            // 2. 예상 해시 계산
            String expectedHash = schemaIo.generateSchemaHash(latestSchema);
            String baselineHash = schemaIo.getBaselineHash();

            // 3. 데이터베이스 적용 상태 확인 및 검증
            if (verification.isSchemaUpToDate(expectedHash, baselineHash)) {
                System.out.println("Schema is up to date");
                System.out.println("   Hash: " + expectedHash);
                return 0;
            } else {
                String appliedHash = verification.getAppliedSchemaHash(expectedHash, baselineHash);
                System.out.println("Schema mismatch detected");
                System.out.println("   Expected: " + expectedHash);
                System.out.println("   Applied:  " + appliedHash);
                System.out.println("   Run migration to synchronize.");
                return 1;
            }

        } catch (Exception e) {
            System.err.println("Verification failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

}