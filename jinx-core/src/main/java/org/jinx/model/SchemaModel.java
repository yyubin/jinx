package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
public class SchemaModel {
    private String version;
    @Builder.Default
    private Map<String, EntityModel> entities = new HashMap<>();
    @JsonCreator
    public SchemaModel(@JsonProperty("version") String version,
                       @JsonProperty("entities") Map<String, EntityModel> entities) {
        this.version = version;
        this.entities = entities != null ? entities : new ConcurrentHashMap<>();
    }
}
