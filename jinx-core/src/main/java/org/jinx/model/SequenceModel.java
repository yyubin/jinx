package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
public class SequenceModel {
    private String name;
    @Builder.Default private String schema = null;
    @Builder.Default private String catalog = null;
    private long initialValue;
    private int allocationSize;
    private int cache;
    private long minValue;
    private long maxValue;

    @JsonCreator
    public SequenceModel(
            @JsonProperty("name") String name,
            @JsonProperty("schema") String schema,
            @JsonProperty("catalog") String catalog,
            @JsonProperty("initialValue") long initialValue,
            @JsonProperty("allocationSize") int allocationSize,
            @JsonProperty("cache") int cache,
            @JsonProperty("minValue") long minValue,
            @JsonProperty("maxValue") long maxValue) {
        this.name = name;
        this.schema = schema;
        this.catalog = catalog;
        this.initialValue = initialValue;
        this.allocationSize = allocationSize;
        this.cache = cache;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }
}