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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityModel {
    private String entityName;
    private String tableName;
    @Builder.Default private String schema = null; // Added for @Table(schema)
    @Builder.Default private String catalog = null; // Added for @Table(catalog)
    private InheritanceType inheritance;
    private String parentEntity;
    @Builder.Default private TableType tableType = TableType.ENTITY;
    @Builder.Default private Map<String, ColumnModel> columns = new HashMap<>();
    @Builder.Default private Map<String, IndexModel> indexes = new HashMap<>();
    @Builder.Default private List<ConstraintModel> constraints = new ArrayList<>();
    @Builder.Default private List<RelationshipModel> relationships = new ArrayList<>();
    @Builder.Default private boolean isValid = true;
    @Builder.Default private String discriminatorValue = null;

    public enum TableType {
        ENTITY, JOIN_TABLE, COLLECTION_TABLE
    }
}
