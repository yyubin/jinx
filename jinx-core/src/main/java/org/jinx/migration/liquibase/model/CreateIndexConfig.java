package org.jinx.migration.liquibase.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
@Data
@Builder
public class CreateIndexConfig {
    private String indexName;
    private String tableName;
    private Boolean unique;
    private List<ColumnWrapper> columns;
}
