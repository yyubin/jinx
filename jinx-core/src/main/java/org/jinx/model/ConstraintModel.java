package org.jinx.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ConstraintModel {
    private String name;
    private String tableName;
    private ConstraintType type;
    private List<String> columns;
    private String referencedTable;
    private List<String> referencedColumns;
    private OnDeleteAction onDelete;
    private OnUpdateAction onUpdate;

    private String checkClause;
}
