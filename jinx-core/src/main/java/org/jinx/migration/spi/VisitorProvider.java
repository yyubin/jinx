package org.jinx.migration.spi;

import org.jinx.model.DialectBundle;
import org.jinx.model.VisitorProviders;

/**
 * SPI for creating database-specific visitor providers.
 * Implementations should check if they support a given database type
 * and create appropriate visitors for that database.
 */
public interface VisitorProvider {
    /**
     * Checks if this provider supports the given database type.
     *
     * @param bundle the dialect bundle containing database type information
     * @return true if this provider supports the database type, false otherwise
     */
    boolean supports(DialectBundle bundle);

    /**
     * Creates visitor providers for the given database type.
     *
     * @param bundle the dialect bundle containing database type and DDL information
     * @return visitor providers for the database
     * @throws IllegalArgumentException if the database type is not supported
     */
    VisitorProviders create(DialectBundle bundle);
}
