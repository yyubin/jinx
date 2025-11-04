package org.jinx.naming;

import jakarta.persistence.CheckConstraint;

import java.util.List;

public interface Naming {
    String joinTableName(String leftTable, String rightTable);
    
    /**
     * Foreign key column naming strategy.
     *
     * Usage patterns:
     * - Regular entity FK: foreignKeyColumnName(fieldName, referencedPK) - attribute name based
     * - Join table FK: foreignKeyColumnName(entityTableName, referencedPK) - referenced table name based
     *
     * @param ownerName For regular entities: field name, For join tables: referenced entity table name
     * @param referencedPkColumnName Referenced primary key column name
     * @return Foreign key column name
     */
    String foreignKeyColumnName(String ownerName, String referencedPkColumnName);
    String pkName(String tableName, List<String> columns);
    String fkName(String fromTable, List<String> fromColumns, String toTable, List<String> toColumns);
    String uqName(String tableName, List<String> columns);
    String ixName(String tableName, List<String> columns);
    String ckName(String tableName, List<String> columns);
    String ckName(String tableName, CheckConstraint constraint);
    String nnName(String tableName, List<String> columns);
    String dfName(String tableName, List<String> columns);
    String autoName(String tableName, List<String> columns);
}
