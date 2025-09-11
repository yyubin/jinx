package org.jinx.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class IndexModel {
    private String indexName;
    private String tableName;
    private List<String> columnNames;
    private Boolean unique;
    private String where;
    private String type;
}
