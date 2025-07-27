package org.jinx.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RelationshipModel {
    private String type;
    private String column;
    private String referencedTable;
    private String referencedColumn;
    private String constraintName;
    @Builder.Default
    private boolean mapsId = false;
}
