package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddUniqueConstraintConfig {
    private String constraintName;
    private String tableName;
    private String columnNames;
}