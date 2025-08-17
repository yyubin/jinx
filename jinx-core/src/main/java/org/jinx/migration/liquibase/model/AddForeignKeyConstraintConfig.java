package org.jinx.migration.liquibase.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AddForeignKeyConstraintConfig {
    private String constraintName;
    private String baseTableName;
    private String baseColumnNames;
    private String referencedTableName;
    private String referencedColumnNames;
    private String onDelete;
    private String onUpdate;
}
