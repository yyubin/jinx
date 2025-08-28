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
}
