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
                .map(SecondaryTableModel::getName)
                .filter(name -> name != null && !name.isEmpty())
                .anyMatch(name -> tableNameToValidate.equalsIgnoreCase(name));
    }

    private String colKey(String tableName, String columnName) {
        String normalizedTableName = (tableName == null || tableName.isEmpty()) ? this.tableName : tableName;
        return normalizedTableName + "::" + columnName;
    }

    public ColumnModel findColumn(String tableName, String columnName) {
        return columns.get(colKey(tableName, columnName));
    }

    public void putColumn(ColumnModel column) {
        columns.put(colKey(column.getTableName(), column.getColumnName()), column);
    }

    public boolean hasColumn(String tableName, String columnName) {
        return columns.containsKey(colKey(tableName, columnName));
    }
}
