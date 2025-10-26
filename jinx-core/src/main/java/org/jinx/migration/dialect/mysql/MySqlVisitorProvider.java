package org.jinx.migration.dialect.mysql;

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


public final class MySqlVisitorProvider implements VisitorProvider {

    @Override
    public boolean supports(DialectBundle bundle) {
        return bundle.databaseType() == DatabaseType.MYSQL;
    }

    @Override
    public VisitorProviders create(DialectBundle bundle) {
        var ddl = bundle.ddl();

        Supplier<TableVisitor> tableV =
                () -> new MySqlMigrationVisitor((DiffResult.ModifiedEntity) null, ddl);

        Function<DiffResult.ModifiedEntity, TableContentVisitor> contentV =
                me -> new MySqlMigrationVisitor(me, ddl);

        Function<EntityModel, TableContentVisitor> entityContentV =
                me -> new MySqlMigrationVisitor(me, ddl);

        // 시퀀스: MySQL 미지원
        Optional<Supplier<SequenceVisitor>> seqV = Optional.empty();

        var tgOpt = bundle.tableGenerator().map(tgDialect ->
                (Supplier<TableGeneratorVisitor>) () -> new MySqlTableGeneratorVisitor(tgDialect)
        );

        return new VisitorProviders(tableV, contentV, entityContentV, seqV, tgOpt);
    }
}
