package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.DatabaseType;
import org.jinx.migration.spi.VisitorProvider;
import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.VisitorProviders;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PostgreSqlVisitorProvider implements VisitorProvider {

    @Override
    public boolean supports(DialectBundle bundle) {
        return bundle.databaseType() == DatabaseType.POSTGRESQL;
    }

    @Override
    public VisitorProviders create(DialectBundle bundle) {
        var ddl = bundle.ddl();

        Supplier<TableVisitor> tableV =
                () -> new PostgreSqlMigrationVisitor((DiffResult.ModifiedEntity) null, ddl);

        Function<DiffResult.ModifiedEntity, TableContentVisitor> contentV =
                me -> new PostgreSqlMigrationVisitor(me, ddl);

        Function<EntityModel, TableContentVisitor> entityContentV =
                me -> new PostgreSqlMigrationVisitor(me, ddl);

        Optional<Supplier<SequenceVisitor>> seqV = bundle.sequence()
                .map(seqDialect -> () -> new PostgreSqlSequenceVisitor(seqDialect));

        Optional<Supplier<TableGeneratorVisitor>> tgV = bundle.tableGenerator()
                .map(tgDialect -> () -> new PostgreSqlTableGeneratorVisitor(tgDialect));

        return new VisitorProviders(tableV, contentV, entityContentV, seqV, tgV);
    }
}
