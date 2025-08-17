package org.jinx.migration.dialect.mysql;

import org.jinx.migration.TableGeneratorBuilder;
import org.jinx.migration.contributor.alter.TableGeneratorModifyContributor;
import org.jinx.migration.contributor.create.TableGeneratorAddContributor;
import org.jinx.migration.contributor.drop.TableGeneratorDropContributor;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.model.TableGeneratorModel;

public class MySqlTableGeneratorVisitor implements TableGeneratorVisitor {

    private final TableGeneratorBuilder builder;

    public MySqlTableGeneratorVisitor(TableGeneratorDialect tgDialect) {
        this.builder = new TableGeneratorBuilder(tgDialect);
    }

    @Override public void visitAddedTableGenerator(TableGeneratorModel tg) {
        builder.add(new TableGeneratorAddContributor(tg));
    }
    @Override public void visitDroppedTableGenerator(TableGeneratorModel tg) {
        builder.add(new TableGeneratorDropContributor(tg));
    }
    @Override public void visitModifiedTableGenerator(TableGeneratorModel n, TableGeneratorModel o) {
        builder.add(new TableGeneratorModifyContributor(n, o));
    }

    @Override public String getGeneratedSql() { return builder.build(); }
}
