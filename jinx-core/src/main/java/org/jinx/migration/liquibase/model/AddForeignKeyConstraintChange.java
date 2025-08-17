package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AddForeignKeyConstraintChange {
    @JsonProperty("addForeignKeyConstraint")
    private AddForeignKeyConstraintConfig config;
}
