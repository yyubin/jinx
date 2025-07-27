package org.jinx.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
public class EntityModel {
    private String entityName;
    private String tableName;
    private String inheritance;
    private String parentEntity;
    @Builder.Default
    private boolean isJoinTable = false;
    @Builder.Default
    private Map<String, ColumnModel> columns = new ConcurrentHashMap<>();
    @Builder.Default
    private Map<String, IndexModel> indexes = new ConcurrentHashMap<>();
    @Builder.Default
    private List<ConstraintModel> constraints = new CopyOnWriteArrayList<>();
    @Builder.Default
    private List<RelationshipModel> relationships = new CopyOnWriteArrayList<>();
}
