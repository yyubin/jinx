package org.jinx.migration;

import org.jinx.model.*;

import java.util.Collection;
import java.util.List;

public interface Dialect {
    MigrationVisitor createVisitor(DiffResult.ModifiedEntity diff);
    ValueTransformer getValueTransformer();

    /* Sequence */
    default String getCreateSequenceSql(SequenceModel seq) { return ""; }
    default String getDropSequenceSql(SequenceModel seq) { return ""; }
    default String getAlterSequenceSql(SequenceModel newSeq, SequenceModel oldSeq) { return ""; }

    /* TableGenerator */
    default String getCreateTableGeneratorSql(TableGeneratorModel tg) { return ""; }
    default String getDropTableGeneratorSql(TableGeneratorModel tg) { return ""; }
    default String getAlterTableGeneratorSql(TableGeneratorModel newTg, TableGeneratorModel oldTg) { return ""; }

    String getCreateTableSql(EntityModel entity);
    String getDropTableSql(EntityModel entity);
    String getDropTableSql(String tableName);
    String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity);
    String getRenameTableSql(String oldTableName, String newTableName); // New method
    String preSchemaObjects(SchemaModel schema);
    JavaTypeMapper getJavaTypeMapper();
    String openCreateTable(String tableName);
    String closeCreateTable();
    String indexStatement(IndexModel idx, String table);
    String quoteIdentifier(String raw);
    String getColumnDefinitionSql(ColumnModel column);
    String getPrimaryKeyDefinitionSql(List<String> pkColumns);
    String getConstraintDefinitionSql(ConstraintModel constraint);
    String getAddColumnSql(String table, ColumnModel column);
    String getDropColumnSql(String table, ColumnModel column);
    String getModifyColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn);
    String getRenameColumnSql(String table, ColumnModel newColumn, ColumnModel oldColumn);
    String getAddConstraintSql(String table, ConstraintModel constraint);
    String getDropConstraintSql(String table, ConstraintModel constraint);
    String getModifyConstraintSql(String table, ConstraintModel newConstraint, ConstraintModel oldConstraint);
    String getDropIndexSql(String table, IndexModel index);
    String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex);
    String getAddPrimaryKeySql(String table, List<String> pkColumns);
    String getDropPrimaryKeySql(String table);
    String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns);
    String getAddRelationshipSql(String table, RelationshipModel rel);
    String getDropRelationshipSql(String table, RelationshipModel rel);
    String getModifyRelationshipSql(String table, RelationshipModel newRel, RelationshipModel oldRel);
}