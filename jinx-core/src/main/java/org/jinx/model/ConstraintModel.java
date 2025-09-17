package org.jinx.model;

import lombok.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class ConstraintModel {
    private String name;
    private String schema;
    private String tableName;
    private ConstraintType type;
    @Builder.Default private List<String> columns = new ArrayList<>();
    private String referencedTable;
    @Builder.Default private List<String> referencedColumns = new ArrayList<>();
    private OnDeleteAction onDelete;
    private OnUpdateAction onUpdate;
    private String checkClause;
    private String where;
    private String options;
}
