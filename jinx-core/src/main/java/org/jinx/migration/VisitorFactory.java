package org.jinx.migration;

import org.jinx.migration.dialect.mysql.MySqlMigrationVisitor;
import org.jinx.migration.dialect.mysql.MySqlTableGeneratorVisitor;
import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.VisitorProviders;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class VisitorFactory {
    /**
     * Builds VisitorProviders configured from the given DialectBundle.
     *
     * The returned providers produce dialect-specific visitors (table, table content,
     * optional sequence and table-generator visitors) based on the bundle's
     * database type and DDL/auxiliary dialects.
     *
     * For the MYSQL database type this creates:
     * - a TableVisitor supplier that constructs MySqlMigrationVisitor(null, ddl)
     * - a Function producing TableContentVisitor by constructing MySqlMigrationVisitor(me, ddl)
     * - an empty Optional for SequenceVisitor (sequences are not supported for MySQL)
     * - an Optional TableGeneratorVisitor supplier when bundle.tableGenerator() is present
     *
     * @param bundle the DialectBundle containing databaseType, ddl, and optional table generator dialect
     * @return a VisitorProviders instance configured for the bundle's database type
     * @throws IllegalArgumentException if the bundle's database type is not supported
     */
    public static VisitorProviders forBundle(DialectBundle bundle) {
        var db = bundle.databaseType();
        var ddl = bundle.ddl();

        switch (db) {
            case MYSQL -> {
                Supplier<TableVisitor> tableV =
                        () -> new MySqlMigrationVisitor(null, ddl);

                Function<DiffResult.ModifiedEntity, TableContentVisitor> contentV =
                        me -> new MySqlMigrationVisitor(me, ddl);

                // 시퀀스: MySQL 미지원
                Optional<Supplier<SequenceVisitor>> seqV = Optional.empty();

                var tgOpt = bundle.tableGenerator().map(tgDialect ->
                        (Supplier<TableGeneratorVisitor>) () -> new MySqlTableGeneratorVisitor(tgDialect)
                );

                return new VisitorProviders(tableV, contentV, seqV, tgOpt);
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + db);
        }
    }


}
