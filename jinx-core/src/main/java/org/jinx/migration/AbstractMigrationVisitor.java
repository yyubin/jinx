package org.jinx.migration;

import lombok.Getter;
import org.jinx.migration.contributor.drop.DropTableStatementContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

import java.util.StringJoiner;

public abstract class AbstractMigrationVisitor implements SqlGeneratingVisitor {
    protected final DdlDialect ddlDialect;
    protected final StringJoiner sql;
    private boolean alterFlushed = false;

    @Getter
    protected AlterTableBuilder alterBuilder;

    protected AbstractMigrationVisitor(DdlDialect ddlDialect, DiffResult.ModifiedEntity diff) {
        this.ddlDialect = ddlDialect;
        this.sql = new StringJoiner("\n");
        if (diff != null) {
            this.alterBuilder = new AlterTableBuilder(diff.getNewEntity().getTableName(), ddlDialect);
        } else {
            this.alterBuilder = null;
        }
    }

    protected AbstractMigrationVisitor(DdlDialect ddlDialect, EntityModel entity) {
        this.ddlDialect = ddlDialect;
        this.sql = new StringJoiner("\n");
        if (entity != null) {
            this.alterBuilder = new AlterTableBuilder(entity.getTableName(), ddlDialect);
        } else {
            this.alterBuilder = null;
        }
    }

    public void visitAddedTable(EntityModel table) {
        CreateTableBuilder builder = new CreateTableBuilder(table.getTableName(), ddlDialect).defaultsFrom(table);
        sql.add(builder.build());
    }

    public void visitDroppedTable(EntityModel table) {
        DropTableBuilder builder = new DropTableBuilder(ddlDialect);
        builder.add(new DropTableStatementContributor(table.getTableName()));
        sql.add(builder.build());
    }

    @Override
    public String getGeneratedSql() {
        String alterSql = alterBuilder != null ? alterBuilder.build() : "";
        if (!alterFlushed && !alterSql.isEmpty()) {
            sql.add(alterSql);
            alterFlushed = true;
        }
        return sql.toString();
    }
}
