package org.jinx.migration;

import org.jinx.model.*;
import org.jinx.annotation.*;

public class MigrationGenerator {

    private final Dialect dialect;

    public MigrationGenerator(Dialect dialect) {
        this.dialect = dialect;
    }

    public String generateSql(DiffResult diffResult) {
        StringBuilder sql = new StringBuilder();

        // 1. Dropped Tables (DROP TABLE)
        for (EntityModel table : diffResult.getDroppedTables()) {
            sql.append(dialect.getDropTableSql(table)).append("\n");
        }

        // 2. Added Tables (CREATE TABLE)
        for (EntityModel table : diffResult.getAddedTables()) {
            sql.append(dialect.getCreateTableSql(table)).append("\n");
        }

        // 3. Modified Tables (ALTER TABLE)
        for (DiffResult.ModifiedEntity modified : diffResult.getModifiedTables()) {
            sql.append(dialect.getAlterTableSql(modified)).append("\n");
        }

        return sql.toString().trim();
    }
}