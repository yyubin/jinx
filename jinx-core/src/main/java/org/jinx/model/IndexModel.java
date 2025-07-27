package org.jinx.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class IndexModel {
    private String indexName;
    private List<String> columnNames;
    private boolean isUnique;
}
