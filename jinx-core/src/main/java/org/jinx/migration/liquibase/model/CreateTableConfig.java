package org.jinx.migration.liquibase.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data
@Builder
public class CreateTableConfig {
    private String tableName;
    private List<ColumnWrapper> columns;
}