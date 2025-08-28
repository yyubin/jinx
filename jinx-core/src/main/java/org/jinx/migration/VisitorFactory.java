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
