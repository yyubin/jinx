package org.jinx.migration.liquibase.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateSequenceConfig {
    private String sequenceName;
    private String startValue;
    private String incrementBy;
}
