package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.InheritanceType;
import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class EntityModel {
    private String entityName;

    private String tableName;

    @Builder.Default
    private String schema = null; // Added for @Table(schema)

    @Builder.Default
    private String catalog = null; // Added for @Table(catalog)

    @Builder.Default
    private String comment = null; // Added for @Table(comment)

    private InheritanceType inheritance;

    private String parentEntity;

    @Builder.Default
    private String fqcn = null;

    @Builder.Default
    private TableType tableType = TableType.ENTITY;

    @Setter(lombok.AccessLevel.NONE)
    @Builder.Default
    private Map<ColumnKey, ColumnModel> columns = new HashMap<>();

    @Builder.Default
    private Map<String, IndexModel> indexes = new HashMap<>();

    @Builder.Default
    private Map<String, ConstraintModel> constraints = new HashMap<>();

    @Builder.Default
    private Map<String, RelationshipModel> relationships = new HashMap<>();

    @Builder.Default
    private List<SecondaryTableModel> secondaryTables = new ArrayList<>();

    @Builder.Default
    private boolean isValid = true;

    @Builder.Default
    private String discriminatorValue = null;

    public enum TableType {
        ENTITY, JOIN_TABLE, COLLECTION_TABLE
    }

    public boolean isValidTableName(String tableNameToValidate) {
        // An empty/null table name implies the default primary table, which is always valid.
        if (tableNameToValidate == null || tableNameToValidate.isBlank()) {
            return true;
        }

        // Check if it matches the primary table name (case-insensitive).
        final String t = tableNameToValidate.trim();
        if (t.equalsIgnoreCase(this.tableName)) {
            return true;
        }

        // Check if it matches any of the registered secondary table names (case-insensitive).
        return secondaryTables.stream()
                .map(SecondaryTableModel::getName)
                .filter(name -> name != null && !name.isEmpty())
                .anyMatch(name -> t.equalsIgnoreCase(name));
    }

    private ColumnKey colKey(String tableName, String columnName) {
        String normalizedTableName = (tableName == null || tableName.isBlank()) ? this.tableName : tableName;
        return ColumnKey.of(normalizedTableName, columnName);
    }

    public ColumnModel findColumn(String tableName, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return null; // null/blank 컬럼명은 찾을 수 없음
        }
        return columns.get(colKey(tableName, columnName));
    }

    public void putColumn(ColumnModel column) {
        if (column == null || column.getColumnName() == null || column.getColumnName().isBlank()) {
            throw new IllegalArgumentException("columnName must not be null/blank");
        }
        columns.put(colKey(column.getTableName(), column.getColumnName()), column);
    }

    public boolean hasColumn(String tableName, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return false; // null/blank 컬럼명은 존재하지 않음
        }
        return columns.containsKey(colKey(tableName, columnName));
    }

    @JsonIgnore
    public boolean isJavaBackedEntity() {
        return fqcn != null && !fqcn.isBlank() && tableType == TableType.ENTITY;
    }
    
    /**
     * 읽기 전용 컬럼 맵 반환 (외부에서 수정 불가)
     */
    @JsonIgnore
    public Map<ColumnKey, ColumnModel> getColumnsReadOnly() {
        return java.util.Collections.unmodifiableMap(columns);
    }
    
    /**
     * 모든 컬럼 제거
     */
    public void clearColumns() {
        this.columns.clear();
    }
    
    /**
     * 테스트 전용 helper 메서드: String 키를 ColumnKey로 변환하여 컬럼 추가
     */
    public void setColumnFromMap(Map<String, ColumnModel> columnMap) {
        this.columns.clear();
        columnMap.forEach((key, column) -> putColumn(column));
    }
}
