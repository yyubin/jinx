package org.jinx.migration.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.model.SchemaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Manages schema baselines and hash-based verification
 */
public class BaselineManager {

    private static final String BASELINE_FILE = "schema-baseline.json";
    private static final String BASELINE_METADATA_FILE = "baseline-metadata.json";

    private final Path outputDir;
    private final ObjectMapper objectMapper;

    public BaselineManager(Path outputDir) {
        this.outputDir = outputDir;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate deterministic hash for schema content
     */
    public String generateSchemaHash(SchemaModel schema) {
        try {
            // Create deterministic JSON representation
            String content = objectMapper.writeValueAsString(schema);

            // Generate SHA-256 hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString(); // Use full 64-character SHA-256 hash

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate schema hash", e);
        }
    }

    /**
     * Load current baseline schema, or create initial baseline if none exists
     */
    public SchemaModel loadBaseline() throws IOException {
        Path baselineFile = outputDir.resolve(BASELINE_FILE);

        if (!Files.exists(baselineFile)) {
            return createInitialBaseline();
        }

        return objectMapper.readValue(baselineFile.toFile(), SchemaModel.class);
    }

    /**
     * Get baseline hash from metadata
     */
    public Optional<String> getBaselineHash() {
        try {
            Path metadataFile = outputDir.resolve(BASELINE_METADATA_FILE);
            if (!Files.exists(metadataFile)) {
                return Optional.empty();
            }

            BaselineMetadata metadata = objectMapper.readValue(metadataFile.toFile(), BaselineMetadata.class);
            return Optional.of(metadata.getSchemaHash());

        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Promote current HEAD schema to baseline
     */
    public void promoteBaseline(SchemaModel headSchema, String schemaHash) throws IOException {
        // Ensure output directory exists
        Files.createDirectories(outputDir);

        // Save baseline schema
        Path baselineFile = outputDir.resolve(BASELINE_FILE);
        objectMapper.writeValue(baselineFile.toFile(), headSchema);

        // Save baseline metadata
        BaselineMetadata metadata = BaselineMetadata.builder()
                .schemaHash(schemaHash)
                .version(headSchema.getVersion())
                .promotedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();

        Path metadataFile = outputDir.resolve(BASELINE_METADATA_FILE);
        objectMapper.writeValue(metadataFile.toFile(), metadata);
    }

    /**
     * Check if baseline exists
     */
    public boolean hasBaseline() {
        return Files.exists(outputDir.resolve(BASELINE_FILE));
    }

    /**
     * Create initial empty baseline for first run
     */
    private SchemaModel createInitialBaseline() {
        return SchemaModel.builder()
                .version("initial")
                .build();
    }

    /**
     * Baseline metadata for tracking promotion info
     */
    public static class BaselineMetadata {
        private String schemaHash;
        private String version;
        private String promotedAt;

        // Getters and setters
        public String getSchemaHash() { return schemaHash; }
        public void setSchemaHash(String schemaHash) { this.schemaHash = schemaHash; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getPromotedAt() { return promotedAt; }
        public void setPromotedAt(String promotedAt) { this.promotedAt = promotedAt; }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final BaselineMetadata metadata = new BaselineMetadata();

            public Builder schemaHash(String schemaHash) {
                metadata.setSchemaHash(schemaHash);
                return this;
            }

            public Builder version(String version) {
                metadata.setVersion(version);
                return this;
            }

            public Builder promotedAt(String promotedAt) {
                metadata.setPromotedAt(promotedAt);
                return this;
            }

            public BaselineMetadata build() {
                return metadata;
            }
        }
    }
}