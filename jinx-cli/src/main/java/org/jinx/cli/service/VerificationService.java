package org.jinx.cli.service;

import org.jinx.migration.integration.MigrationToolIntegration;

/**
 * Service for verifying database schema application status.
 * Checks if migrations have been applied to the database by querying migration tool metadata.
 */
public class VerificationService {

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String migrationTool;

    /**
     * Creates a new verification service.
     *
     * @param dbUrl database connection URL
     * @param dbUser database username
     * @param dbPassword database password
     * @param migrationTool the migration tool being used (jinx, liquibase, flyway)
     */
    public VerificationService(String dbUrl, String dbUser, String dbPassword, String migrationTool) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.migrationTool = migrationTool != null ? migrationTool : "jinx";
    }

    /**
     * Gets the hash of the schema that is currently applied to the database.
     * If database connection is not available, returns the baseline hash.
     *
     * @param expectedHash the expected schema hash
     * @param baselineHash the baseline schema hash
     * @return the applied schema hash
     */
    public String getAppliedSchemaHash(String expectedHash, String baselineHash) {
        if (dbUrl == null || dbUser == null) {
            return baselineHash;
        }

        try {
            // Check if this hash has been applied via the specified migration tool
            boolean isApplied = switch (migrationTool.toLowerCase()) {
                case "liquibase" -> MigrationToolIntegration.isAppliedViaLiquibase(dbUrl, dbUser, dbPassword, expectedHash);
                case "flyway" -> MigrationToolIntegration.isAppliedViaFlyway(dbUrl, dbUser, dbPassword, expectedHash);
                case "jinx" -> MigrationToolIntegration.isAppliedViaJinxState(dbUrl, dbUser, dbPassword, expectedHash);
                default -> false;
            };

            return isApplied ? expectedHash : baselineHash;

        } catch (Exception e) {
            System.err.println("Warning: Could not check database state - " + e.getMessage());
            return baselineHash;
        }
    }

    /**
     * Checks if the schema is up to date with what has been applied to the database.
     *
     * @param expectedHash the expected schema hash
     * @param baselineHash the baseline schema hash
     * @return true if the schema is up to date, false otherwise
     */
    public boolean isSchemaUpToDate(String expectedHash, String baselineHash) {
        String appliedHash = getAppliedSchemaHash(expectedHash, baselineHash);
        return expectedHash.equals(appliedHash);
    }

    /**
     * Records that a schema has been applied to the database.
     * Only records if using jinx migration tool and database connection is available.
     *
     * @param schemaHash the hash of the applied schema
     * @param version the version of the applied schema
     */
    public void recordSchemaApplication(String schemaHash, String version) {
        if (dbUrl != null && dbUser != null && "jinx".equals(migrationTool.toLowerCase())) {
            try {
                MigrationToolIntegration.recordSchemaApplication(dbUrl, dbUser, dbPassword, schemaHash, version);
            } catch (Exception e) {
                System.err.println("Warning: Could not record schema application - " + e.getMessage());
            }
        }
    }
}