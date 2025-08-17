package org.jinx.migration.output;

import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Path;

public interface OutputHandler {
    void handle(DiffResult diff, SchemaModel oldSchema, SchemaModel newSchema, DialectBundle dialect, Path outputDir) throws IOException;
}
