package org.jinx.migration;

import org.jinx.model.*;
import java.util.StringJoiner;

import static org.jinx.migration.DiffResult.TableGeneratorDiff.Type.ADDED;

public class MigrationGenerator {
    private final Dialect dialect;
    private final SchemaModel newSchema;

    public MigrationGenerator(Dialect dialect, SchemaModel newSchema) {
        this.dialect = dialect;
        this.newSchema = newSchema;
    }

    public String generateSql(DiffResult diff) {
        StringJoiner sql = new StringJoiner("\n");

        // 1. Generate sequence and table generator DDL (before tables for dependencies)
        sql.add(dialect.preSchemaObjects(newSchema));

        // 2. Handle sequence diffs (for dialects like PostgreSQL)
        for (DiffResult.SequenceDiff seqDiff : diff.getSequenceDiffs()) {
            switch (seqDiff.getType()) {
                case ADDED -> sql.add(dialect.getCreateSequenceSql(seqDiff.getSequence()));
                case DROPPED -> sql.add(dialect.getDropSequenceSql(seqDiff.getSequence()));
                case MODIFIED -> sql.add(dialect.getAlterSequenceSql(seqDiff.getSequence(), seqDiff.getOldSequence()));
            }
        }

        // 3. Handle table generator diffs (for MySQL, only ADDED is relevant)
        for (DiffResult.TableGeneratorDiff tgDiff : diff.getTableGeneratorDiffs()) {
            switch (tgDiff.getType()) {
                case DROPPED -> sql.add(dialect.getDropTableGeneratorSql(tgDiff.getTableGenerator()));
                case MODIFIED -> sql.add(dialect.getAlterTableGeneratorSql(tgDiff.getTableGenerator(), tgDiff.getOldTableGenerator()));
                default -> {}
            }
        }

        // 4. Handle table drops
        for (EntityModel dropped : diff.getDroppedTables()) {
            sql.add(dialect.getDropTableSql(dropped));
        }

        // 5. Handle table additions
        for (EntityModel added : diff.getAddedTables()) {
            sql.add(dialect.getCreateTableSql(added));
        }

        // 6. Handle table modifications
        for (DiffResult.ModifiedEntity modified : diff.getModifiedTables()) {
            sql.add(dialect.getAlterTableSql(modified));
        }

        // 7. Add warnings as SQL comments
        for (String warning : diff.getAllWarnings()) {
            sql.add("-- WARNING: " + warning);
        }

        return sql.toString();
    }
}