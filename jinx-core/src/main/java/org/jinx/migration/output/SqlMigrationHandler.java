package org.jinx.migration.output;

import org.jinx.model.DialectBundle;
import org.jinx.migration.MigrationGenerator;
import org.jinx.migration.MigrationInfo;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SqlMigrationHandler implements OutputHandler{
    @Override
    public void handle(DiffResult diff, SchemaModel old, SchemaModel next, DialectBundle dialect, Path outputDir) throws IOException {
        handle(diff, old, next, dialect, outputDir, null);
    }

    /**
     * Handle with migration info for header generation
     */
    public void handle(DiffResult diff, SchemaModel old, SchemaModel next, DialectBundle dialect, Path outputDir, MigrationInfo migrationInfo) throws IOException {
        String sql = new MigrationGenerator(dialect, next, false).generateSql(diff);

        // Add header if migration info is provided
        if (migrationInfo != null) {
            sql = generateHeader(migrationInfo) + "\n\n" + sql;
        }

        Files.createDirectories(outputDir);

        // Generate filename with hash for Flyway compatibility
        String filename;
        if (migrationInfo != null) {
            // Flyway pattern: V1__description__jinxHead_sha256_hash.sql
            filename = String.format("V%s__migration__jinxHead_sha256_%s.sql",
                next.getVersion(), migrationInfo.getHeadHash());
        } else {
            // Fallback to simple format
            filename = "migration-" + next.getVersion() + ".sql";
        }

        Files.writeString(outputDir.resolve(filename), sql);
    }

    private String generateHeader(MigrationInfo info) {
        return String.format("""
            -- Jinx Migration Header
            -- jinx:baseline=sha256:%s
            -- jinx:head=sha256:%s
            -- jinx:version=%s
            -- jinx:generated=%s
            """,
            info.getBaselineHash(),
            info.getHeadHash(),
            info.getVersion(),
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }
}
