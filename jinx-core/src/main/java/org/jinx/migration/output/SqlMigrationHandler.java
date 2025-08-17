package org.jinx.migration.output;

import org.jinx.model.DialectBundle;
import org.jinx.migration.MigrationGenerator;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SqlMigrationHandler implements OutputHandler{
    @Override
    public void handle(DiffResult diff, SchemaModel old, SchemaModel next, DialectBundle dialect, Path outputDir) throws IOException {
        String sql = new MigrationGenerator(dialect, next, false).generateSql(diff);
        Files.writeString(outputDir.resolve("migration-" + next.getVersion() + ".sql"), sql);
    }
}
