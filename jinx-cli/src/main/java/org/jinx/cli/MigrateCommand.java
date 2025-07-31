package org.jinx.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.migration.Dialect;
import org.jinx.model.DiffResult;
import org.jinx.migration.MigrationGenerator;
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

    @CommandLine.Option(
            names = {"-p", "--path"},
            description = "스키마 JSON 파일 폴더",
            defaultValue = "build/classes/java/main/jinx"
    )
    private Path schemaDir;

    @CommandLine.Option(
            names = "--dialect",
            description = "사용할 DB 방언(mysql, postgres …)",
            defaultValue = "mysql"
    )
    private String dialectName;

    @CommandLine.Option(
            names = "--out",
            description = "생성된 migration.sql 저장 위치",
            defaultValue = "build/jinx"
    )
    private Path outputDir;

    @CommandLine.Option(
            names = "--force",
            description = "데이터 손실 위험이 있는 변경 사항(예: Enum 타입 변경)을 강제로 실행합니다."
    )
    private boolean force;

    @Override public Integer call() {
        try {
            if (!Files.exists(schemaDir)) {
                System.err.println("Schema directory not found: " + schemaDir);
                return 1;
            }

            List<Path> schemas = Files.list(schemaDir)
                    .filter(p -> p.getFileName().toString().matches("schema-\\d{14}\\.json"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();

            if (schemas.size() < 2) {
                System.out.println("Need at least two schema files for comparison.");
                return 0;
            }

            SchemaModel newSchema = loadSchema(schemas.get(0));
            SchemaModel oldSchema = loadSchema(schemas.get(1));

            DiffResult diff = new SchemaDiffer().diff(oldSchema, newSchema);
            if (!isChanged(diff)) {
                System.out.println("No changes detected.");
                return 0;
            }

            // --- 위험한 변경 감지 로직 추가 ---
            List<String> dangerousChanges = findDangerousChanges(diff);
            if (!dangerousChanges.isEmpty() && !force) {
                System.err.println("⚠️  데이터 손실 위험이 있는 변경이 감지되어 중단합니다.");
                dangerousChanges.forEach(change -> System.err.println("   - " + change));
                System.err.println("\n   계속하려면 --force 옵션을 지정하세요.");
                return 1;
            }

            Dialect dialect = switch (dialectName.toLowerCase()) {
                case "mysql" -> new MySqlDialect();
                default -> throw new IllegalArgumentException("Unsupported dialect: " + dialectName);
            };

            String sql = new MigrationGenerator(dialect, newSchema).generateSql(diff);

            Files.createDirectories(outputDir);
            Path outFile = outputDir.resolve("migration-" + newSchema.getVersion() + ".sql");
            Files.writeString(outFile, sql);

            System.out.println("Migration SQL written to: " + outFile.toAbsolutePath());
            return 0;

        } catch (Exception e) {
            System.err.println("Migration failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private List<String> findDangerousChanges(DiffResult diff) {
        return diff.getModifiedTables().stream()
                .flatMap(m -> m.getColumnDiffs().stream()
                        .filter(cd -> cd.getChangeDetail() != null && cd.getChangeDetail().contains("Enum mapping changed"))
                        .map(cd -> String.format("테이블 '%s'의 컬럼 '%s': %s",
                                m.getNewEntity().getTableName(),
                                cd.getColumn().getColumnName(),
                                cd.getChangeDetail()))
                )
                .collect(Collectors.toList());
    }

    private SchemaModel loadSchema(Path path) throws IOException {
        return new ObjectMapper().readValue(path.toFile(), SchemaModel.class);
    }

    private boolean isChanged(DiffResult r) {
        return !(r.getAddedTables().isEmpty() &&
                r.getDroppedTables().isEmpty() &&
                r.getModifiedTables().isEmpty());
    }
}
