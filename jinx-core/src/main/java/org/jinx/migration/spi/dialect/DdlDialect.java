package org.jinx.migration.spi.dialect;

import org.jinx.descriptor.*;
import org.jinx.model.*;

import java.util.Collection;
import java.util.List;

public interface DdlDialect extends BaseDialect{
    // Table
    String openCreateTable(String tableName);
    String closeCreateTable();
    String getCreateTableSql(EntityModel entity);
    String getDropTableSql(String tableName);
    String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity);
    String getRenameTableSql(String oldTableName, String newTableName);

    // Column
    String getColumnDefinitionSql(ColumnModel column);
    String getAddColumnSql(String table, ColumnModel column);
    String getDropColumnSql(String table, ColumnModel column);
    String getModifyColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn);
    String getRenameColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn);

    // Primary Key
    String getPrimaryKeyDefinitionSql(List<String> pkColumns);
    String getAddPrimaryKeySql(String table, List<String> pkColumns);
    String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns);

    // Constraints & Indexes
    String getConstraintDefinitionSql(ConstraintModel constraint);
    String getAddConstraintSql(String table, ConstraintModel constraint);
    String getDropConstraintSql(String table, ConstraintModel constraint);
    String getModifyConstraintSql(String table, ConstraintModel newConstraint, ConstraintModel oldConstraint);
    String indexStatement(IndexModel idx, String table);
    String getDropIndexSql(String table, IndexModel index);
    String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex);

    // Relationships
    String getAddRelationshipSql(String table, RelationshipModel rel);
    String getDropRelationshipSql(String table, RelationshipModel rel);
    String getModifyRelationshipSql(String table, RelationshipModel newRel, RelationshipModel oldRel);
}