package org.jinx.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class RelationshipModel {
    private RelationshipType type;
    private String column;
    private String referencedTable;
    private String referencedColumn;
    private String constraintName;
    @Builder.Default private boolean mapsId = false;
    @Builder.Default private List<CascadeType> cascadeTypes = new ArrayList<>(); // Added for cascade
    @Builder.Default private boolean orphanRemoval = false; // Added for orphanRemoval
    @Builder.Default private FetchType fetchType = FetchType.LAZY; // Added for fetch
}
