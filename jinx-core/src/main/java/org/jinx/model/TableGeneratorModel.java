package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
public class TableGeneratorModel {
    private String name;
    private String table;
    private String schema;
    private String catalog;
    private String pkColumnName;
    private String valueColumnName;
    private String pkColumnValue;
    private long initialValue;
    private int allocationSize;

    @JsonCreator
    public TableGeneratorModel(
            @JsonProperty("name") String name,
            @JsonProperty("table") String table,
            @JsonProperty("schema") String schema,
            @JsonProperty("catalog") String catalog,
            @JsonProperty("pkColumnName") String pkColumnName,
            @JsonProperty("valueColumnName") String valueColumnName,
            @JsonProperty("pkColumnValue") String pkColumnValue,
            @JsonProperty("initialValue") long initialValue,
            @JsonProperty("allocationSize") int allocationSize) {
        this.name = name;
        this.table = table;
        this.schema = schema;
        this.catalog = catalog;
        this.pkColumnName = pkColumnName;
        this.valueColumnName = valueColumnName;
        this.pkColumnValue = pkColumnValue;
        this.initialValue = initialValue;
        this.allocationSize = allocationSize;
    }
}