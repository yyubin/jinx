package org.jinx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor
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
    @Builder.Default private Optional<String> checkClause = Optional.empty();
    @Builder.Default private Optional<String> where = Optional.empty();
    @Builder.Default private Optional<String> options = Optional.empty();
}
