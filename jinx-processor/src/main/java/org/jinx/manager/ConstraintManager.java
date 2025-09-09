package org.jinx.manager;

import org.jinx.context.ProcessingContext;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.util.ConstraintKeys;

import java.util.List;
import java.util.Optional;

public class ConstraintManager {
    private final ProcessingContext context;

    public ConstraintManager(ProcessingContext context) {
        this.context = context;
    }

    public void addUniqueIfAbsent(EntityModel entity, String table, List<String> cols, Optional<String> whereOpt) {
        String key = ConstraintKeys.canonicalKey(
                ConstraintType.UNIQUE.name(),
                entity.getSchema(),
                table,
                cols,
                whereOpt.orElse(null)
        );
        if (entity.getConstraints().containsKey(key)) return;

        String name = context.getNaming().uqName(table, cols);
        ConstraintModel c = ConstraintModel.builder()
                .name(name)
                .schema(entity.getSchema())
                .tableName(table)
                .type(ConstraintType.UNIQUE)
                .columns(cols)
                .where(whereOpt)
                .build();

        entity.getConstraints().put(key, c);
    }

    public void removeUniqueIfPresent(EntityModel entity, String table, List<String> cols, Optional<String> whereOpt) {
        String key = ConstraintKeys.canonicalKey(
                ConstraintType.UNIQUE.name(),
                entity.getSchema(),
                table,
                cols,
                whereOpt.orElse(null)
        );
        entity.getConstraints().remove(key);
    }

    public Optional<ConstraintModel> findUnique(EntityModel entity, String table, List<String> cols, Optional<String> whereOpt) {
        String key = ConstraintKeys.canonicalKey(
                ConstraintType.UNIQUE.name(),
                entity.getSchema(),
                table,
                cols,
                whereOpt.orElse(null)
        );
        return Optional.ofNullable(entity.getConstraints().get(key));
    }
}
