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
    private String tableName; // Added for FK constraint table (where FK is created)
    private List<String> columns;
    private String referencedTable;
    private List<String> referencedColumns;
    private String constraintName;
    private OnDeleteAction onDelete;
    private OnUpdateAction onUpdate;
    @Builder.Default private boolean mapsId = false;
    @Builder.Default private boolean noConstraint = false; // Added for @ForeignKey(NO_CONSTRAINT)
    @Builder.Default private List<CascadeType> cascadeTypes = new ArrayList<>(); // Added for cascade
    @Builder.Default private boolean orphanRemoval = false; // Added for orphanRemoval
    @Builder.Default private FetchType fetchType = FetchType.LAZY; // Added for fetch
}
