package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

@FunctionalInterface
public interface Differ {
    void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result);
}