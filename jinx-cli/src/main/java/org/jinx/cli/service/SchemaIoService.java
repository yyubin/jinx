package org.jinx.cli.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.migration.baseline.BaselineManager;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SchemaIoService {

    private static final String SCHEMA_FILE_PATTERN = "schema-\\d{14}\\.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Path schemaDir;
    private final Path outputDir;

    public SchemaIoService(Path schemaDir, Path outputDir) {
        this.schemaDir = schemaDir;
        this.outputDir = outputDir;
    }

    public SchemaModel loadLatestSchema() throws IOException {
        if (!Files.exists(schemaDir)) {
            return null;
        }

        try(var stream = Files.list(schemaDir)) {
            List<Path> schemaPaths = stream
                    .filter(p -> p.getFileName().toString().matches(SCHEMA_FILE_PATTERN))
                    .sorted((a,b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .limit(1)
                    .toList();

            if (schemaPaths.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(schemaPaths.get(0).toFile(), SchemaModel.class);
        } catch (IOException e) {
            System.err.println("Warning: Failed to scan schemaDir " + schemaDir + " - " + e.getMessage());
            return null;
        }
    }

    public SchemaModel loadBaselineSchema() throws IOException {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        return baselineManager.loadBaseline();
    }

    public String generateSchemaHash(SchemaModel schema) {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        return baselineManager.generateSchemaHash(schema);
    }

    public String getBaselineHash() {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        return baselineManager.getBaselineHash().orElse("initial");
    }

    public void promoteToBaseline(SchemaModel schema, String schemaHash) throws IOException {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        baselineManager.promoteBaseline(schema, schemaHash);
    }
}