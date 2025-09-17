package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexModel {
    private String indexName;
    private String tableName;
    private List<String> columnNames;
    private Boolean unique;
    private String where;
    private String type;
}
