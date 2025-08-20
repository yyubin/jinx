package org.jinx.handler.builtins;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.TableLike;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class SecondaryTableAdapter implements TableLike {
    private final SecondaryTable secondaryTable;
    private final ProcessingContext context;

    public SecondaryTableAdapter(SecondaryTable table, ProcessingContext context) {
        this.secondaryTable = table;
        this.context = context;
    }

    @Override
    public String getName() {
        return secondaryTable.name();
    }

    @Override
    public Optional<String> getSchema() {
        return Optional.ofNullable(secondaryTable.schema()).filter(s -> !s.isEmpty());
    }

    @Override
    public Optional<String> getCatalog() {
        return Optional.ofNullable(secondaryTable.catalog()).filter (c -> !c.isEmpty ());
    }

    @Override
    public Optional<String> getComment() {
        return Optional.ofNullable(secondaryTable.comment()).filter(c -> !c.isEmpty());
    }

    @Override
    public List<ConstraintModel> getConstraints() {
        List<ConstraintModel> constraints = new ArrayList<>();
        for (UniqueConstraint uc : secondaryTable.uniqueConstraints()) {
            constraints.add(ConstraintModel.builder()
                    .name(uc.name().isBlank() ? context.getNaming().uqName(secondaryTable.name(), List.of(uc.columnNames())) : uc.name())
                    .type(ConstraintType.UNIQUE)
                    .columns(Arrays.asList(uc.columnNames()))
                    .build());
        }
        for (CheckConstraint cc : secondaryTable.check()) {
            constraints.add(ConstraintModel.builder()
                    .name(cc.name().isBlank() ? context.getNaming().ckName(secondaryTable.name(), cc) : cc.name())
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
        for (Index idx : secondaryTable.indexes()) {
            indexes.add(IndexModel.builder()
                    .indexName(idx.name())
                    .columnNames(Arrays.asList(idx.columnList().split(",\\s*")))
                    .isUnique(idx.unique())
                    .build());
        }
        return indexes;
    }
}
