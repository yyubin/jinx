package org.jinx.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.migration.DatabaseType;
import org.jinx.model.DialectBundle;
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

    @Override
    public Integer call() {
        try {
            // 1. 스키마 파일 로드 및 기본 검증
            List<SchemaModel> schemas = loadAndValidateSchemas();
            if (schemas.isEmpty()) {
                System.out.println("No changes detected or not enough schema files to compare.");
                return 0;
            }
            SchemaModel oldSchema = schemas.get(1);
            SchemaModel newSchema = schemas.get(0);

            // 2. 스키마 비교 및 변경점 확인
            DiffResult diff = new SchemaDiffer().diff(oldSchema, newSchema);
            if (!isChanged(diff)) {
                System.out.println("No changes detected.");
                return 0;
            }

            // 3. 위험한 변경 감지 및 처리
            handleDangerousChanges(diff);

            // 4. 마이그레이션 파일 생성
            generateMigrationOutputs(diff, oldSchema, newSchema);

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

    private List<SchemaModel> loadAndValidateSchemas() throws IOException {
        if (!Files.exists(schemaDir)) {
            throw new IOException("Schema directory not found: " + schemaDir);
        }

        List<Path> schemaPaths = Files.list(schemaDir)
                .filter(p -> p.getFileName().toString().matches(SCHEMA_FILE_PATTERN))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .limit(2) // 최신 2개만 가져옴
                .toList();

        if (schemaPaths.size() < 2) {
            return List.of();
        }

        SchemaModel newSchema = loadSchema(schemaPaths.get(0));
        SchemaModel oldSchema = loadSchema(schemaPaths.get(1));

        return List.of(newSchema, oldSchema);
    }


    // 데이터 손실 위험이 있는 변경 사항을 감지하고, --force 옵션이 없으면 예외
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

    private void generateMigrationOutputs(DiffResult diff, SchemaModel oldSchema, SchemaModel newSchema) throws IOException {
        var bundle = resolveDialects(dialectName);
        new SqlMigrationHandler().handle(diff, oldSchema, newSchema, bundle, outputDir);

        if (generateRollback) {
            new SqlRollbackHandler().handle(diff, oldSchema, newSchema, bundle, outputDir);
        }

        if (generateLiquibase) {
            new LiquibaseYamlHandler().handle(diff, oldSchema, newSchema, bundle, outputDir);
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