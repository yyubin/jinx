package org.jinx.migration.liquibase.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data
@Builder
public class ColumnConfig {
    private String name;
    private String type;
    private String defaultValue;
    private String defaultValueSequenceNext;
    private String defaultValueComputed;
    private Constraints constraints;
    private Boolean autoIncrement;
}