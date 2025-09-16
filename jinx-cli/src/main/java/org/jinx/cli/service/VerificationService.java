package org.jinx.cli.service;

import org.jinx.migration.integration.MigrationToolIntegration;

public class VerificationService {

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String migrationTool;

    public VerificationService(String dbUrl, String dbUser, String dbPassword, String migrationTool) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.migrationTool = migrationTool != null ? migrationTool : "jinx";
    }

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

    public boolean isSchemaUpToDate(String expectedHash, String baselineHash) {
        String appliedHash = getAppliedSchemaHash(expectedHash, baselineHash);
        return expectedHash.equals(appliedHash);
    }

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