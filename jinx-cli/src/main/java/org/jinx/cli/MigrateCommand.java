package org.jinx.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.migration.DatabaseType;
import org.jinx.model.DialectBundle;
import org.jinx.options.JinxOptions;
import org.jinx.naming.DefaultNaming;
import org.jinx.config.ConfigurationLoader;
import org.jinx.migration.baseline.BaselineManager;
import org.jinx.migration.MigrationInfo;
import org.jinx.migration.output.LiquibaseYamlHandler;
import org.jinx.migration.output.SqlMigrationHandler;
import org.jinx.migration.output.SqlRollbackHandler;
import org.jinx.model.DiffResult;
import org.jinx.migration.differs.SchemaDiffer;
import org.jinx.migration.dialect.mysql.MySqlDialect;
import org.jinx.model.SchemaModel;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * Command for generating database migration SQL files.
 * Detects entity changes and creates appropriate migration scripts.
 */
@CommandLine.Command(
        name = "migrate",
        mixinStandardHelpOptions = true,
        showDefaultValues = true,
        description = "엔티티 변경 사항을 감지하여 마이그레이션 SQL을 생성합니다."
)
public class MigrateCommand implements Callable<Integer> {

    private static final String SCHEMA_FILE_PATTERN = "schema-\\d{14}\\.json";

    @CommandLine.Option(names = {"-p", "--path"}, description = "스키마 JSON 파일 폴더", defaultValue = "build/classes/java/main/jinx")
    private Path schemaDir;
    @CommandLine.Option(names = {"-d", "--dialect"}, description = "사용할 DB 방언(mysql, postgres …)", defaultValue = "mysql")
    private String dialectName;
    @CommandLine.Option(names = "--out", description = "생성된 migration.sql 저장 위치", defaultValue = "build/jinx")
    private Path outputDir;
    @CommandLine.Option(names = "--force", description = "데이터 손실 위험이 있는 변경 사항(예: Enum 타입 변경)을 강제로 실행합니다.")
    private boolean force;
    @CommandLine.Option(names = "--rollback", description = "롤백 SQL도 함께 생성합니다.")
    private boolean generateRollback;
    @CommandLine.Option(names = "--liquibase", description = "Liquibase YAML을 함께 생성합니다.")
    private boolean generateLiquibase;
    @CommandLine.Option(names = "--max-length", description = "생성되는 제약조건/인덱스 이름의 최대 길이", defaultValue = "30")
    private int maxLength = JinxOptions.Naming.MAX_LENGTH_DEFAULT;
    @CommandLine.Option(names = "--profile", description = "사용할 설정 프로파일 (dev, prod, test 등)")
    private String profile;

    @Override
    public Integer call() {
        try {
            // Load and apply configuration
            applyConfiguration();

            // Compare baseline vs HEAD
            BaselineManager baselineManager = new BaselineManager(outputDir);
            SchemaModel baseline = baselineManager.loadBaseline();
            SchemaModel head = loadLatestSchema();

            if (head == null) {
                System.out.println("No HEAD schema found. Run compilation first.");
                return 0;
            }

            // Detect schema changes
            DiffResult diff = new SchemaDiffer().diff(baseline, head);
            if (!isChanged(diff)) {
                System.out.println("No changes detected.");
                return 0;
            }

            // Handle potentially dangerous changes
            handleDangerousChanges(diff);

            // Generate migration files with hash information
            generateMigrationOutputs(diff, baseline, head, baselineManager);

            System.out.println("Migration files generated successfully in " + outputDir);
            return 0;

        } catch (DangerousChangeException e) {
            System.err.println("⚠️ Migration aborted due to potentially dangerous changes.");
            e.getReasons().forEach(reason -> System.err.println("   - " + reason));
            System.err.println("\n   To proceed anyway, use the --force option.");
            return 1;
        } catch (Exception e) {
            System.err.println("Migration failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Loads the latest (HEAD) schema file from the schema directory.
     *
     * @return the latest schema model, or null if no schema files exist
     * @throws IOException if an I/O error occurs
     */
    private SchemaModel loadLatestSchema() throws IOException {
        if (!Files.exists(schemaDir)) {
            return null;
        }

        List<Path> schemaPaths = Files.list(schemaDir)
                .filter(p -> p.getFileName().toString().matches(SCHEMA_FILE_PATTERN))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .limit(1)
                .toList();

        if (schemaPaths.isEmpty()) {
            return null;
        }

        return loadSchema(schemaPaths.get(0));
    }

    /**
     * Detects potentially dangerous changes (e.g., enum type modifications).
     * Throws an exception if dangerous changes are found and --force option is not set.
     *
     * @param diff the schema diff result
     * @throws DangerousChangeException if dangerous changes are detected without force flag
     */
    private void handleDangerousChanges(DiffResult diff) throws DangerousChangeException {
        List<String> dangerousChanges = diff.getModifiedTables().stream()
                .flatMap(m -> m.getColumnDiffs().stream()
                        .filter(cd -> cd.getChangeDetail() != null && cd.getChangeDetail().contains("Enum mapping changed"))
                        .map(cd -> String.format("Table '%s', Column '%s': %s",
                                m.getNewEntity().getTableName(),
                                cd.getColumn().getColumnName(),
                                cd.getChangeDetail()))
                )
                .collect(Collectors.toList());

        if (!dangerousChanges.isEmpty() && !force) {
            throw new DangerousChangeException(dangerousChanges);
        }
    }

    private void generateMigrationOutputs(DiffResult diff, SchemaModel baseline, SchemaModel head, BaselineManager baselineManager) throws IOException {
        var bundle = resolveDialects(dialectName);
        var naming = new DefaultNaming(maxLength);

        // Generate hashes for header
        String baselineHash = baselineManager.getBaselineHash().orElse("initial");
        String headHash = baselineManager.generateSchemaHash(head);

        // Create migration info for headers
        MigrationInfo migrationInfo = new MigrationInfo(baselineHash, headHash, head.getVersion());

        // Generate migration SQL with header
        new SqlMigrationHandler().handle(diff, baseline, head, bundle, outputDir, migrationInfo);

        if (generateRollback) {
            new SqlRollbackHandler().handle(diff, baseline, head, bundle, outputDir);
        }

        if (generateLiquibase) {
            new LiquibaseYamlHandler().handle(diff, baseline, head, bundle, outputDir, naming, migrationInfo);
        }
    }


    private SchemaModel loadSchema(Path path) throws IOException {
        return new ObjectMapper().readValue(path.toFile(), SchemaModel.class);
    }

    private boolean isChanged(DiffResult r) {
        return !(r.getAddedTables().isEmpty() &&
                r.getDroppedTables().isEmpty() &&
                r.getModifiedTables().isEmpty());
    }

    private static class DangerousChangeException extends Exception {
        private final List<String> reasons;

        public DangerousChangeException(List<String> reasons) {
            super("Dangerous changes detected");
            this.reasons = reasons;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }

    /**
     * Loads configuration from file and applies to CLI options.
     * Configuration values are only used when CLI options are not explicitly specified.
     */
    private void applyConfiguration() {
        ConfigurationLoader loader = new ConfigurationLoader();
        Map<String, String> config = loader.loadConfiguration(profile);

        // Use config value if CLI maxLength is at default
        if (maxLength == JinxOptions.Naming.MAX_LENGTH_DEFAULT) {
            String configMaxLength = config.get(JinxOptions.Naming.MAX_LENGTH_KEY);
            if (configMaxLength != null) {
                try {
                    maxLength = Integer.parseInt(configMaxLength);
                } catch (NumberFormatException e) {
                    System.err.println("Warning: Invalid maxLength in configuration: " + configMaxLength +
                                     ". Using default: " + JinxOptions.Naming.MAX_LENGTH_DEFAULT);
                }
            }
        }
    }

    private DialectBundle resolveDialects(String name) {
        return switch (name.toLowerCase()) {
            case "mysql" -> {
                MySqlDialect mysql = new MySqlDialect();
                yield DialectBundle.builder(mysql, DatabaseType.MYSQL)
                        .identity(mysql)
                        .tableGenerator(mysql)
                        .build();
            }
            default -> throw new IllegalArgumentException("Unsupported dialect: " + name);
        };
    }
}