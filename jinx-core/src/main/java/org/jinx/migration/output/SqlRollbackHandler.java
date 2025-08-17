package org.jinx.migration.output;

import org.jinx.model.DialectBundle;
import org.jinx.migration.MigrationGenerator;
import org.jinx.migration.differs.SchemaDiffer;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SqlRollbackHandler implements OutputHandler{
    @Override
    public void handle(DiffResult diff, SchemaModel old, SchemaModel next, DialectBundle dialect, Path outputDir) throws IOException {
        DiffResult rollbackDiff = new SchemaDiffer().diff(next, old); // 순서 반전
        String rollbackSql = new MigrationGenerator(dialect, old, true).generateSql(rollbackDiff);
        Files.writeString(outputDir.resolve("rollback-" + next.getVersion() + ".sql"), rollbackSql);
    }
}
