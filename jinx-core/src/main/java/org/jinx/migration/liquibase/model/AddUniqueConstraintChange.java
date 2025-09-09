package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddUniqueConstraintChange implements Change{
    @JsonProperty("addUniqueConstraint")
    private AddUniqueConstraintConfig config;
}