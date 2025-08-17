package org.jinx.migration.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.jinx.model.DialectBundle;
import org.jinx.migration.liquibase.LiquibaseYamlGenerator;
import org.jinx.migration.liquibase.model.DatabaseChangeLog;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Path;

public class LiquibaseYamlHandler implements OutputHandler{
    @Override
    public void handle(DiffResult diff, SchemaModel oldSchema, SchemaModel newSchema, DialectBundle dialect, Path outputDir) throws IOException {
        DatabaseChangeLog yaml = new LiquibaseYamlGenerator().generate(diff, oldSchema, newSchema, dialect);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
        Path outputFile = outputDir.resolve("changelog-" + System.currentTimeMillis() + ".yaml");
        mapper.writeValue(outputFile.toFile(), yaml);
    }
}
