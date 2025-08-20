package org.jinx.context;

import jakarta.persistence.CheckConstraint;
import org.jinx.annotation.Constraint;

import java.util.List;

public interface Naming {
    String joinTableName(String leftTable, String rightTable);
    String foreignKeyColumnName(String ownerName, String referencedPkColumnName);
    String pkName(String tableName, List<String> columns);
    String fkName(String fromTable, List<String> fromColumns, String toTable, List<String> toColumns);
    String uqName(String tableName, List<String> columns);
    String ixName(String tableName, List<String> columns);
    String ckName(String tableName, List<String> columns);
    String ckName(String tableName, CheckConstraint constraint);
}
