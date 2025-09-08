package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.lang.model.element.TypeElement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@NoArgsConstructor
public class SchemaModel {
    private String version;
    @Builder.Default
    private Map<String, EntityModel> entities = new HashMap<>();
    @Builder.Default
    private Map<String, SequenceModel> sequences = new LinkedHashMap<>();
    @Builder.Default
    private Map<String, TableGeneratorModel> tableGenerators = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, ClassInfoModel> mappedSuperclasses = new HashMap<>();

    @Builder.Default
    private Map<String, ClassInfoModel> embeddables = new HashMap<>();

    @JsonCreator
    public SchemaModel(
            @JsonProperty("version")             String version,
            @JsonProperty("entities")            Map<String, EntityModel> entities,
            @JsonProperty("sequences")           Map<String, SequenceModel> sequences,
            @JsonProperty("tableGenerators")     Map<String, TableGeneratorModel> tableGenerators,
            @JsonProperty("mappedSuperclasses")  Map<String, ClassInfoModel> mappedSuperclasses,
            @JsonProperty("embeddables")         Map<String, ClassInfoModel> embeddables) {

        this.version            = version;
        this.entities           = entities != null ? entities : new ConcurrentHashMap<>();
        this.sequences          = sequences != null ? sequences : new LinkedHashMap<>();
        this.tableGenerators    = tableGenerators != null ? tableGenerators : new LinkedHashMap<>();
        this.mappedSuperclasses = mappedSuperclasses != null ? mappedSuperclasses : new HashMap<>();
        this.embeddables        = embeddables != null ? embeddables : new HashMap<>();
    }
}
