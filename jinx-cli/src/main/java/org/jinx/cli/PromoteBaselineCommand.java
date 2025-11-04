package org.jinx.cli;

import org.jinx.cli.service.SchemaIoService;
import org.jinx.cli.service.VerificationService;
import org.jinx.model.SchemaModel;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Command for promoting the current HEAD schema to baseline.
 * Requires successful verification unless --force option is used.
 */
@CommandLine.Command(
        name = "promote-baseline",
        mixinStandardHelpOptions = true,
        description = "현재 HEAD 스키마를 baseline으로 승격합니다. (검증 성공 시에만)"
)
public class PromoteBaselineCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-p", "--path"}, description = "스키마 JSON 파일 폴더", defaultValue = "build/classes/java/main/jinx")
    private Path schemaDir;

    @CommandLine.Option(names = "--out", description = "baseline 파일 저장 위치", defaultValue = "build/jinx")
    private Path outputDir;

    @CommandLine.Option(names = "--force", description = "검증 없이 강제로 승격")
    private boolean force;

    @CommandLine.Option(names = "--db-url", description = "데이터베이스 URL (검증용)")
    private String dbUrl;

    @CommandLine.Option(names = "--db-user", description = "데이터베이스 사용자명")
    private String dbUser;

    @CommandLine.Option(names = "--db-password", description = "데이터베이스 비밀번호")
    private String dbPassword;

    @CommandLine.Option(names = "--migration-tool", description = "사용하는 마이그레이션 도구", defaultValue = "jinx")
    private String migrationTool;

    @Override
    public Integer call() {
        try {
            SchemaIoService schemaIo = new SchemaIoService(schemaDir, outputDir);
            VerificationService verification = new VerificationService(dbUrl, dbUser, dbPassword, migrationTool);

            // Verify schema before promotion (unless force flag is set)
            if (!force) {
                SchemaModel latestSchema = schemaIo.loadLatestSchema();
                if (latestSchema == null) {
                    System.err.println("No HEAD schema found. Run compilation first.");
                    return 1;
                }

                String expectedHash = schemaIo.generateSchemaHash(latestSchema);
                String baselineHash = schemaIo.getBaselineHash();

                if (!verification.isSchemaUpToDate(expectedHash, baselineHash)) {
                    System.err.println("Cannot promote baseline - schema verification failed");
                    System.err.println("   Use --force to promote anyway, or apply migration first");
                    return 1;
                }
            }

            // Load latest schema (required even with force flag)
            SchemaModel latestSchema = schemaIo.loadLatestSchema();
            if (latestSchema == null) {
                System.err.println("No HEAD schema found. Run compilation first.");
                return 1;
            }

            // Promote to baseline
            String schemaHash = schemaIo.generateSchemaHash(latestSchema);
            schemaIo.promoteToBaseline(latestSchema, schemaHash);

            // Record application in database if using jinx migration tool
            verification.recordSchemaApplication(schemaHash, latestSchema.getVersion());

            System.out.println("Baseline promoted successfully");
            System.out.println("   Version: " + latestSchema.getVersion());
            System.out.println("   Hash: " + schemaHash);

            return 0;

        } catch (Exception e) {
            System.err.println("Baseline promotion failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

}