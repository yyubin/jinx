package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

@FunctionalInterface
public interface EntityComponentDiffer {
    void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result);
}
