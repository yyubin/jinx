package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.util.Arrays;
import java.util.List;

public class SchemaDiffer {
    private final List<Differ> differs;

    public SchemaDiffer() {
        this.differs = Arrays.asList(
                new TableDiffer(),
                new EntityModificationDiffer(),
                new SequenceDiffer(),
                new TableGeneratorDiffer()
        );
    }

    public DiffResult diff(SchemaModel oldSchema, SchemaModel newSchema) {
        DiffResult result = DiffResult.builder().build();

        for (Differ differ : differs) {
            differ.diff(oldSchema, newSchema, result);
        }

        return result;
    }
}
