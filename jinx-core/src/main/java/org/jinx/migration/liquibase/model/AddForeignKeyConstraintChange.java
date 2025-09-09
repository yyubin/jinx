package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AddForeignKeyConstraintChange implements Change{
    @JsonProperty("addForeignKeyConstraint")
    private AddForeignKeyConstraintConfig config;
}
