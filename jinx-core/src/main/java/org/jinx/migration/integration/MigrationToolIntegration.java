package org.jinx.migration.integration;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integration utilities for Liquibase/Flyway migration tools
 */
public class MigrationToolIntegration {

    private static final Pattern HASH_PATTERN =
        Pattern.compile("(?m)^\\s*--\\s*jinx:head=sha256:([0-9a-fA-F]{64})\\s*$");

    private static String normalizeSha256(String h) {
        return h.startsWith("sha256:") ? h : "sha256:" + h;
    }

    /**
     * Check if schema hash has been applied via Liquibase
     */
    public static boolean isAppliedViaLiquibase(String jdbcUrl, String username, String password, String targetHash) {
        String query = """
                SELECT description, labels, comments
                FROM DATABASECHANGELOG
                WHERE exectype = 'EXECUTED'
                ORDER BY dateexecuted DESC
            """;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            String head = normalizeSha256(targetHash);
            String hashPatternLb = "jinxHead=" + head;
            while (rs.next()) {
                String comments = rs.getString("comments");
                if (comments != null && comments.contains(hashPatternLb)) {
                    return true;
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to check Liquibase status", e);
        }

        return false;
    }

    /**
     * Check if schema hash has been applied via Flyway
     */
    public static boolean isAppliedViaFlyway(String jdbcUrl, String username, String password, String targetHash) {
        String query = """
                SELECT version, description, script
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_on DESC
            """;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            String head = normalizeSha256(targetHash);
            String hashPatternFw = "jinxHead_" + head.replace(':','_');
            while (rs.next()) {
                String script = rs.getString("script");
                String description = rs.getString("description");
                // Flyway는 스크립트 파일명을 저장하므로, 파일명이나 description에서 해시 확인해야 함
                if ((script != null && script.contains(hashPatternFw)) || (description != null && description.contains(hashPatternFw))) {
                    return true;
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to check Flyway status", e);
        }

        return false;
    }

    public static boolean isAppliedViaJinxState(String jdbcUrl, String username, String password, String targetHash) {
        // First, ensure table exists
        createJinxStateTableIfNotExists(jdbcUrl, username, password);

        String query = "SELECT COUNT(*) FROM jinx_schema_state WHERE current_hash = ?";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, targetHash);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to check Jinx state", e);
        }
    }

    public static void recordSchemaApplication(String jdbcUrl, String username, String password,
                                             String schemaHash, String version) {
        createJinxStateTableIfNotExists(jdbcUrl, username, password);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            // Get DB-independent username
            String dbUser = conn.getMetaData().getUserName();

            // Detect database type for UPSERT syntax
            String databaseType = conn.getMetaData().getDatabaseProductName().toLowerCase();
            String upsertSql = getUpsertSql(databaseType);

            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                stmt.setInt(1, 1); // id = 1 (singleton row)
                stmt.setString(2, schemaHash); // current_hash
                stmt.setString(3, version); // version
                stmt.setString(4, dbUser); // applied_by

                stmt.executeUpdate();
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to record schema application", e);
        }
    }

    private static String getUpsertSql(String databaseType) {
        if (databaseType.contains("mysql")) {
            return """
                INSERT INTO jinx_schema_state (id, current_hash, version, applied_at, applied_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
                ON DUPLICATE KEY UPDATE
                    current_hash = VALUES(current_hash),
                    version = VALUES(version),
                    applied_at = CURRENT_TIMESTAMP,
                    applied_by = VALUES(applied_by)
                """;
        } else if (databaseType.contains("postgresql")) {
            return """
                INSERT INTO jinx_schema_state (id, current_hash, version, applied_at, applied_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
                ON CONFLICT (id) DO UPDATE SET
                    current_hash = EXCLUDED.current_hash,
                    version = EXCLUDED.version,
                    applied_at = CURRENT_TIMESTAMP,
                    applied_by = EXCLUDED.applied_by
                """;
        } else {
            // Fallback for other databases - simple INSERT (may fail on duplicate)
            return """
                INSERT INTO jinx_schema_state (id, current_hash, version, applied_at, applied_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
                """;
        }
    }

    /**
     * Extract hash from migration file header
     */
    public static Optional<String> extractHashFromMigrationFile(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            Matcher matcher = HASH_PATTERN.matcher(content);
            if (matcher.find()) {
                return Optional.of("sha256:" + matcher.group(1));
            }
        } catch (Exception e) {
            // Ignore file read errors
        }
        return Optional.empty();
    }


    private static void createJinxStateTableIfNotExists(String jdbcUrl, String username, String password) {
        String createTable = """
            CREATE TABLE IF NOT EXISTS jinx_schema_state (
                id SMALLINT PRIMARY KEY CHECK (id = 1),
                current_hash VARCHAR(128) NOT NULL,
                version VARCHAR(50),
                applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                applied_by VARCHAR(100)
            )
            """;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute(createTable);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to create Jinx state table", e);
        }
    }
}
