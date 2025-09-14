package org.jinx.migration.spi.dialect;

import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

public interface LiquibaseDialect extends BaseDialect{
    String getLiquibaseTypeName(ColumnModel column);
    
    /**
     * Determines whether a column should use autoIncrement in Liquibase DDL
     * based on its generation strategy and database capabilities
     */
    default boolean shouldUseAutoIncrement(GenerationStrategy strategy) {
        if (strategy == null) return false;
        return switch (strategy) {
            case IDENTITY -> true;
            case AUTO -> supportsIdentity(); // AUTO maps to IDENTITY if supported
            case SEQUENCE, TABLE, UUID, NONE -> false;
        };
    }
    
    /**
     * Gets the default value expression for UUID generation strategy
     */
    default String getUuidDefaultValue() {
        return null; // Most databases don't support UUID generation in DDL
    }
    
    /**
     * Checks if the database supports IDENTITY columns
     */
    default boolean supportsIdentity() {
        return true; // Most modern databases support IDENTITY
    }

    default boolean supportsDropCheck() { return true; }              // MySQL 8.0.16+ true, 구버전 false
    default boolean allowLobLiteralDefault() { return false; }        // TEXT/BLOB 기본값 리터럴 허용 여부
    default boolean pkDropNeedsName() { return false; }               // PK drop 시 이름 필요 DB 여부
    default boolean preferUniqueConstraintOverUniqueIndex() { return true; } // UNIQUE를 제약으로 갈지 인덱스로 갈지
    default boolean supportsComputedUuidDefault() { return getUuidDefaultValue() != null; }

    /**
     * Gets the SQL type for table generator primary key column
     */
    default String getTableGeneratorPkColumnType() {
        return "VARCHAR(255)";
    }
    
    /**
     * Gets the SQL type for table generator value column  
     */
    default String getTableGeneratorValueColumnType() {
        return "BIGINT";
    }
    
    /**
     * Gets the maximum identifier length for this database
     */
    default int getMaxIdentifierLength() {
        return 63; // Standard SQL default
    }

}
