package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class EntityModificationDiffer implements Differ {
    private final List<EntityComponentDiffer> componentDiffers;

    public EntityModificationDiffer() {
        this.componentDiffers = Arrays.asList(
                new ColumnDiffer(),
                new IndexDiffer(),
                new ConstraintDiffer(),
                new RelationshipDiffer()
        );
    }

    @Override
    public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        newSchema.getEntities().forEach((name, newEntity) -> {
            Optional.ofNullable(oldSchema.getEntities().get(name)).ifPresent(oldEntity -> {
                DiffResult.ModifiedEntity modified = compareEntities(oldEntity, newEntity);
                if (isModified(modified)) {
                    result.getModifiedTables().add(modified);
                    result.getWarnings().addAll(modified.getWarnings());
                }
            });
        });
    }

    private DiffResult.ModifiedEntity compareEntities(EntityModel oldEntity, EntityModel newEntity) {
        DiffResult.ModifiedEntity modified = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        // Check schema/catalog/inheritance changes with Optional
        if (!Optional.ofNullable(oldEntity.getSchema()).equals(Optional.ofNullable(newEntity.getSchema()))) {
            modified.getWarnings().add("Schema changed from " + oldEntity.getSchema() + " to " + newEntity.getSchema() +
                    " for entity " + newEntity.getEntityName());
        }
        if (!Optional.ofNullable(oldEntity.getCatalog()).equals(Optional.ofNullable(newEntity.getCatalog()))) {
            modified.getWarnings().add("Catalog changed from " + oldEntity.getCatalog() + " to " + newEntity.getCatalog() +
                    " for entity " + newEntity.getEntityName());
        }
        if (!Optional.ofNullable(oldEntity.getInheritance()).equals(Optional.ofNullable(newEntity.getInheritance()))) {
            modified.getWarnings().add("Inheritance strategy changed from " + oldEntity.getInheritance() +
                    " to " + newEntity.getInheritance() + " for entity " + newEntity.getEntityName() +
                    "; manual migration required.");
        }

        // Delegate to component differs
        for (EntityComponentDiffer differ : componentDiffers) {
            differ.diff(oldEntity, newEntity, modified);
        }

        return modified;
    }

    private boolean isModified(DiffResult.ModifiedEntity modified) {
        return !modified.getColumnDiffs().isEmpty() ||
                !modified.getIndexDiffs().isEmpty() ||
                !modified.getConstraintDiffs().isEmpty() ||
                !modified.getRelationshipDiffs().isEmpty() ||
                !modified.getWarnings().isEmpty();
    }
}