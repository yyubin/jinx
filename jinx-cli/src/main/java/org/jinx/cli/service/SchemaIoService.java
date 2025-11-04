package org.jinx.cli.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.migration.baseline.BaselineManager;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Service for reading and writing schema files and baseline data.
 * Handles schema file I/O operations and hash generation.
 */
public class SchemaIoService {

    private static final String SCHEMA_FILE_PATTERN = "schema-\\d{14}\\.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Path schemaDir;
    private final Path outputDir;

    /**
     * Creates a new schema I/O service.
     *
     * @param schemaDir directory containing schema JSON files
     * @param outputDir directory for baseline and output files
     */
    public SchemaIoService(Path schemaDir, Path outputDir) {
        this.schemaDir = schemaDir;
        this.outputDir = outputDir;
    }

    /**
     * Loads the latest schema file from the schema directory.
     * Schema files are expected to follow the pattern schema-YYYYMMDDHHMMSS.json.
     *
     * @return the latest schema model, or null if no valid schema files exist
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Loads the baseline schema from the output directory.
     *
     * @return the baseline schema model
     * @throws IOException if an I/O error occurs
     */
    public SchemaModel loadBaselineSchema() throws IOException {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        return baselineManager.loadBaseline();
    }

    /**
     * Generates a hash for the given schema model.
     *
     * @param schema the schema model to hash
     * @return the schema hash string
     */
    public String generateSchemaHash(SchemaModel schema) {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        return baselineManager.generateSchemaHash(schema);
    }

    /**
     * Gets the hash of the current baseline schema.
     *
     * @return the baseline hash, or "initial" if no baseline exists
     */
    public String getBaselineHash() {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        return baselineManager.getBaselineHash().orElse("initial");
    }

    /**
     * Promotes the given schema to become the new baseline.
     *
     * @param schema the schema model to promote
     * @param schemaHash the hash of the schema
     * @throws IOException if an I/O error occurs
     */
    public void promoteToBaseline(SchemaModel schema, String schemaHash) throws IOException {
        BaselineManager baselineManager = new BaselineManager(outputDir);
        baselineManager.promoteBaseline(schema, schemaHash);
    }
}