package org.jinx.manager;

import org.jinx.context.ProcessingContext;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.util.ConstraintKeys;

import java.util.List;
import java.util.Optional;

/**
 * Manages the lifecycle of constraints within an {@link EntityModel}.
 * <p>
 * This manager provides a centralized way to add, remove, and find unique constraints,
 * ensuring that they are identified by a canonical key to prevent duplicates.
 */
public class ConstraintManager {
    private final ProcessingContext context;

    public ConstraintManager(ProcessingContext context) {
        this.context = context;
    }

    /**
     * Adds a unique constraint to the entity model if a constraint with the same
     * canonical key is not already present.
     *
     * @param entity The entity model to modify.
     * @param table The table for the constraint.
     * @param cols The list of columns in the constraint.
     * @param whereOpt An optional WHERE clause for partial/filtered constraints.
     */
    public void addUniqueIfAbsent(EntityModel entity, String table, List<String> cols, Optional<String> whereOpt) {
        String where = whereOpt.orElse(null);
        String key = ConstraintKeys.canonicalKey(
                ConstraintType.UNIQUE.name(),
                entity.getSchema(),
                table,
                cols,
                where
        );
        if (entity.getConstraints().containsKey(key)) return;

        String name = context.getNaming().uqName(table, cols);
        ConstraintModel c = ConstraintModel.builder()
                .name(name)
                .schema(entity.getSchema())
                .tableName(table)
                .type(ConstraintType.UNIQUE)
                .columns(cols)
                .where(where) // can be a nullable string
                .build();

        entity.getConstraints().put(key, c);
    }

    /**
     * Removes a unique constraint from the entity model if it exists.
     *
     * @param entity The entity model to modify.
     * @param table The table of the constraint.
     * @param cols The list of columns in the constraint.
     * @param whereOpt An optional WHERE clause for the constraint.
     */
    public void removeUniqueIfPresent(EntityModel entity, String table, List<String> cols, Optional<String> whereOpt) {
        String where = whereOpt.orElse(null);
        String key = ConstraintKeys.canonicalKey(
                ConstraintType.UNIQUE.name(),
                entity.getSchema(),
                table,
                cols,
                where
        );
        entity.getConstraints().remove(key);
    }

    /**
     * Finds a unique constraint in the entity model based on its canonical key.
     *
     * @param entity The entity model to search.
     * @param table The table of the constraint.
     * @param cols The list of columns in the constraint.
     * @param whereOpt An optional WHERE clause for the constraint.
     * @return An {@link Optional} containing the found {@link ConstraintModel}, or empty if not found.
     */
    public Optional<ConstraintModel> findUnique(EntityModel entity, String table, List<String> cols, Optional<String> whereOpt) {
        String where = whereOpt.orElse(null);
        String key = ConstraintKeys.canonicalKey(
                ConstraintType.UNIQUE.name(),
                entity.getSchema(),
                table,
                cols,
                where
        );
        return Optional.ofNullable(entity.getConstraints().get(key));
    }
}
