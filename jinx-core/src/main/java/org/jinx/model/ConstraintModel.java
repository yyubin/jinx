package org.jinx.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConstraintModel {
    private String name;
    private ConstraintType type;
    private String column;
    private String referencedTable;
    private String referencedColumn;
    private OnDeleteAction onDelete;
    private OnUpdateAction onUpdate;
}
