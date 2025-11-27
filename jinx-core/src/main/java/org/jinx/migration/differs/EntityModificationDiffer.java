package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class EntityModificationDiffer implements Differ {
    private final List<EntityComponentDiffer> componentDiffers;

    public EntityModificationDiffer() {
        this(CaseNormalizer.lower());
    }

    public EntityModificationDiffer(CaseNormalizer normalizer) {
        this.componentDiffers = java.util.List.of(
                new SimpleColumnDiffer(),
                new IndexDiffer(normalizer),
                new ConstraintDiffer(),
                new RelationshipDiffer(normalizer)
        );
    }

    @Override
    public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        var oldEntities = new java.util.LinkedHashMap<>(
                java.util.Optional.ofNullable(oldSchema.getEntities()).orElseGet(java.util.Map::of)
        );
        var newEntities = new java.util.LinkedHashMap<>(
                java.util.Optional.ofNullable(newSchema.getEntities()).orElseGet(java.util.Map::of)
        );

        newEntities.forEach((name, newEntity) -> {
            var oldEntity = oldEntities.get(name);
            if (oldEntity == null) return;

            var modified = compareEntities(oldEntity, newEntity);
            if (isModified(modified)) {
                result.getModifiedTables().add(modified);
                result.getWarnings().addAll(modified.getWarnings());
            }
        });
    }

    private DiffResult.ModifiedEntity compareEntities(EntityModel oldEntity, EntityModel newEntity) {
        var modified = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();

        if (!java.util.Objects.equals(oldEntity.getSchema(), newEntity.getSchema())) {
            modified.getWarnings().add("Schema changed: " + oldEntity.getSchema() + " → " + newEntity.getSchema()
                    + " (entity=" + newEntity.getEntityName() + ")");
        }
        if (!java.util.Objects.equals(oldEntity.getCatalog(), newEntity.getCatalog())) {
            modified.getWarnings().add("Catalog changed: " + oldEntity.getCatalog() + " → " + newEntity.getCatalog()
                    + " (entity=" + newEntity.getEntityName() + ")");
        }
        if (!java.util.Objects.equals(oldEntity.getInheritance(), newEntity.getInheritance())) {
            modified.getWarnings().add("Inheritance strategy changed: " + oldEntity.getInheritance()
                    + " → " + newEntity.getInheritance() + " (entity=" + newEntity.getEntityName() + "); manual migration may be required.");
        }

        for (EntityComponentDiffer differ : componentDiffers) {
            try {
                differ.diff(oldEntity, newEntity, modified);
            } catch (Exception e) {
                modified.getWarnings().add("Differ failed: " + differ.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }
        return modified;
    }

    public void diffPair(EntityModel oldEntity, EntityModel newEntity, DiffResult result) {
        DiffResult.ModifiedEntity modified = compareEntities(oldEntity, newEntity);
        if (isModified(modified)) {
            result.getModifiedTables().add(modified);
            result.getWarnings().addAll(modified.getWarnings());
        }
    }


    private boolean isModified(DiffResult.ModifiedEntity modified) {
        return !modified.getColumnDiffs().isEmpty()
                || !modified.getIndexDiffs().isEmpty()
                || !modified.getConstraintDiffs().isEmpty()
                || !modified.getRelationshipDiffs().isEmpty()
                || !modified.getWarnings().isEmpty();
    }
}