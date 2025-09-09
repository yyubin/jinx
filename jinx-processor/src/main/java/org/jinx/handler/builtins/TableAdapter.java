package org.jinx.handler.builtins;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.TableLike;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class TableAdapter implements TableLike {
    private final Table table;
    private final ProcessingContext context;

    public TableAdapter(Table table, ProcessingContext context) {
        this.table = table;
        this.context = context;
    }

    @Override
    public String getName() {
        return table.name();
    }

    @Override
    public Optional<String> getSchema() {
        return Optional.ofNullable(table.schema()).filter(s -> !s.isEmpty());
    }

    @Override
    public Optional<String> getCatalog() {
        return Optional.ofNullable(table.catalog()).filter(c -> !c.isEmpty());
    }

    @Override
    public Optional<String> getComment() {
        return Optional.ofNullable(table.comment()).filter(c -> !c.isEmpty());
    }

    @Override
    public List<ConstraintModel> getConstraints() {
        List<ConstraintModel> constraints = new ArrayList<>();
        for (UniqueConstraint uc : table.uniqueConstraints()) {
            constraints.add(ConstraintModel.builder()
                    .name(uc.name().isBlank() ? context.getNaming().uqName(table.name(), List.of(uc.columnNames())) : uc.name())
                    .type(ConstraintType.UNIQUE)
                    .columns(Arrays.asList(uc.columnNames()))
                    .build());
        }
        for (CheckConstraint cc : table.check()) {
            constraints.add(ConstraintModel.builder()
                    .name(cc.name().isBlank() ? context.getNaming().ckName(table.name(), cc) : cc.name())
                    .type(ConstraintType.CHECK)
                    .checkClause(Optional.ofNullable(cc.constraint()))
                    .options(Optional.ofNullable(cc.options()))
                    .build());
        }
        return constraints;
    }

    @Override
    public List<IndexModel> getIndexes() {
        List<IndexModel> indexes = new ArrayList<>();
        for (Index idx : table.indexes()) {
            indexes.add(IndexModel.builder()
                    .indexName(idx.name())
                    .columnNames(Arrays.asList(idx.columnList().split(",\\s*")))
                    .build());
        }
        return indexes;
    }
}
