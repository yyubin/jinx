package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateIndexChange implements Change{
    @JsonProperty("createIndex")
    private CreateIndexConfig config;
}
