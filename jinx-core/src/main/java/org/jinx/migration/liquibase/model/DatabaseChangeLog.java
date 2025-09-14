package org.jinx.migration.liquibase.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseChangeLog {
    @JsonProperty("databaseChangeLog")
    private List<ChangeSetWrapper> changeSets;
}