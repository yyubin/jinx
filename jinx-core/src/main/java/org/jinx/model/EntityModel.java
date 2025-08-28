package org.jinx.model;

import jakarta.persistence.InheritanceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityModel {
    private String entityName;
    private String tableName;
    @Builder.Default private String schema = null; // Added for @Table(schema)
    @Builder.Default private String catalog = null; // Added for @Table(catalog)
    @Builder.Default private String comment = null; // Added for @Table(comment)
    private InheritanceType inheritance;
    private String parentEntity;
    @Builder.Default private TableType tableType = TableType.ENTITY;
    @Builder.Default private Map<String, ColumnModel> columns = new HashMap<>();
    @Builder.Default private Map<String, IndexModel> indexes = new HashMap<>();
    @Builder.Default private Map<String, ConstraintModel> constraints = new HashMap<>();
    @Builder.Default private Map<String, RelationshipModel> relationships = new HashMap<>();
    @Builder.Default private List<SecondaryTableModel> secondaryTables = new ArrayList<>();
    @Builder.Default private boolean isValid = true;
    @Builder.Default private String discriminatorValue = null;

    public enum TableType {
        ENTITY, JOIN_TABLE, COLLECTION_TABLE
    }

    /**
     * Determines whether a given table name is valid for this entity.
     *
     * A null or empty name is treated as the entity's primary table and is considered valid.
     * Comparison is case-insensitive: the name is valid if it equals the primary table name
     * or matches any secondary table name registered on this entity.
     *
     * @param tableNameToValidate the table name to validate; may be null or empty to denote the primary table
     * @return true if the name refers to the primary table or any secondary table for this entity, otherwise false
     */
    public boolean isValidTableName(String tableNameToValidate) {
        // An empty/null table name implies the default primary table, which is always valid.
        if (tableNameToValidate == null || tableNameToValidate.isEmpty()) {
            return true;
        }

        // Check if it matches the primary table name (case-insensitive).
        if (tableNameToValidate.equalsIgnoreCase(this.tableName)) {
            return true;
        }

        // Check if it matches any of the registered secondary table names (case-insensitive).
        return secondaryTables.stream()
                .anyMatch(st -> tableNameToValidate.equalsIgnoreCase(st.getName()));
    }

    /**
     * Build a composite key for column lookup by combining a table name and column name.
     *
     * If {@code tableName} is null or empty, the entity's primary {@code tableName} is used.
     *
     * @param tableName the table name to use (may be null or empty to indicate the primary table)
     * @param columnName the column name
     * @return the composite key in the format "<table>::<column>"
     */
    private String colKey(String tableName, String columnName) {
        String normalizedTableName = (tableName == null || tableName.isEmpty()) ? this.tableName : tableName;
        return normalizedTableName + "::" + columnName;
    }

    /**
     * Returns the ColumnModel for the given table and column name, or null if none is found.
     *
     * If tableName is null or empty, the entity's primary table name is used. Table name matching
     * is case-insensitive with respect to how column keys are stored.
     *
     * @param tableName   the table that contains the column, or null/empty to use the primary table
     * @param columnName  the name of the column to look up
     * @return the ColumnModel for the specified table and column, or null if not present
     */
    public ColumnModel findColumn(String tableName, String columnName) {
        return columns.get(colKey(tableName, columnName));
    }

    /**
     * Adds or replaces metadata for a column in this entity.
     *
     * The entry is keyed by the column's table name and column name. If the
     * provided ColumnModel has a null or empty table name, the entity's primary
     * table name is used. If an entry already exists for the same table+column,
     * it will be overwritten.
     *
     * @param column the ColumnModel to store (table name may be null/empty to target the primary table)
     */
    public void putColumn(ColumnModel column) {
        columns.put(colKey(column.getTableName(), column.getColumnName()), column);
    }

    /**
     * Returns true if a column with the given name exists for the specified table.
     *
     * If {@code tableName} is null or empty the entity's primary table is used. Table name matching is performed
     * via the internal table/column composite key logic, so secondary table names are supported.
     *
     * @param tableName   the table name to look up, or null/empty to use the primary table
     * @param columnName  the column name to check for
     * @return            {@code true} if the column exists for the resolved table, otherwise {@code false}
     */
    public boolean hasColumn(String tableName, String columnName) {
        return columns.containsKey(colKey(tableName, columnName));
    }
}
