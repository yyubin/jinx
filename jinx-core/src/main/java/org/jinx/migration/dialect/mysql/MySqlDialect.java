package org.jinx.migration.dialect.mysql;

import lombok.Getter;
import org.jinx.migration.*;
import org.jinx.migration.internal.create.ColumnContributor;
import org.jinx.migration.internal.create.ConstraintContributor;
import org.jinx.migration.internal.create.IndexContributor;
import org.jinx.migration.internal.drop.DropTableStatementContributor;
import org.jinx.model.*;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MySqlDialect extends AbstractDialect {

    private Map<String, ColumnModel> currentColumns = Collections.emptyMap();

    public MySqlDialect() {
        super();
    }

    private void setCurrentColumns(Collection<ColumnModel> cols) {
        this.currentColumns = cols.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c));
    }

    // 테스트용 생성자
    public MySqlDialect(JavaTypeMapper javaTypeMapper, ValueTransformer valueTransformer) {
        this.javaTypeMapper   = javaTypeMapper;
        this.valueTransformer = valueTransformer;
    }

    @Override
    protected JavaTypeMapper initializeJavaTypeMapper() {
        return new MySqlJavaTypeMapper();
    }

    @Override
    protected ValueTransformer initializeValueTransformer() {
        return new MySqlValueTransformer();
    }

    @Override
    public JavaTypeMapper getJavaTypeMapper() {
        return javaTypeMapper;
    }

    @Override
    public String getCreateTableSql(EntityModel entity) {
        this.setCurrentColumns(entity.getColumns().values());
        CreateTableBuilder builder = new CreateTableBuilder(this);

        List<ColumnModel> cols = entity.getColumns().values().stream().toList();
        List<String> pkCols = cols.stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .toList();

        builder.add(new ColumnContributor(cols, pkCols));
        builder.add(new ConstraintContributor(entity.getConstraints().stream().toList()));
        builder.add(new IndexContributor(
                entity.getTableName(),
                entity.getIndexes().values().stream().toList()));

        return builder.build(entity.getTableName());
    }

    @Override
    public String getDropTableSql(EntityModel entity) {
        DropTableBuilder builder = new DropTableBuilder(this);
        builder.add(new DropTableStatementContributor(entity.getTableName()));
        return builder.build();
    }

    @Override
    public String getDropTableSql(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteIdentifier(tableName) + ";\n";
    }

    @Override
    public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) {
        this.setCurrentColumns(modifiedEntity.getNewEntity().getColumns().values());
        MySqlMigrationVisitor visitor = new MySqlMigrationVisitor(modifiedEntity, this);
        modifiedEntity.accept(visitor);
        return visitor.getAlterBuilder().build();
    }

    @Override
    public String preSchemaObjects(SchemaModel schema) {
        StringBuilder ddl = new StringBuilder();
        for (TableGeneratorModel tg : schema.getTableGenerators().values()) {
            ddl.append("CREATE TABLE IF NOT EXISTS ")
                    .append(quoteIdentifier(tg.getTable()))
                    .append(" (")
                    .append(quoteIdentifier(tg.getPkColumnName())).append(" VARCHAR(255) NOT NULL PRIMARY KEY, ")
                    .append(quoteIdentifier(tg.getValueColumnName())).append(" BIGINT NOT NULL")
                    .append(");\n");

            ddl.append("INSERT IGNORE INTO ")
                    .append(quoteIdentifier(tg.getTable()))
                    .append(" (").append(quoteIdentifier(tg.getPkColumnName()))
                    .append(", ").append(quoteIdentifier(tg.getValueColumnName())).append(")")
                    .append(" VALUES ('").append(valueTransformer.quote(tg.getPkColumnValue(), new MySqlJavaTypeMapper().map("java.lang.String")))
                    .append("', ").append(tg.getInitialValue()).append(");\n");
        }
        return ddl.toString();
    }

    // --- CREATE/DROP TABLE 기본 구문 ---

    @Override
    public String openCreateTable(String table) {
        return "CREATE TABLE " + quoteIdentifier(table) + " (\n";
    }

    @Override
    public String closeCreateTable() {
        return "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";
    }

    @Override
    public String quoteIdentifier(String raw) {
        return "`" + raw + "`";
    }

    @Override
    protected String getIdentityClause(ColumnModel c) {
        return " AUTO_INCREMENT";
    }

    // --- CREATE TABLE 내부 절(clause) 생성 메서드 ---

    @Override
    public String getPrimaryKeyDefinitionSql(List<String> pk) {
        if (pk == null || pk.isEmpty()) return "";
        if (pk.size() == 1 && columnIsIdentity(pk.get(0))) {
            return "";
        }
        return super.getPrimaryKeyDefinitionSql(pk);
    }

    private boolean columnIsIdentity(String colName) {
        ColumnModel col = currentColumns.get(colName);
        return col != null && col.getGenerationStrategy() == GenerationStrategy.IDENTITY;
    }

    @Override
    public String getConstraintDefinitionSql(ConstraintModel cons) {
        StringBuilder sb = new StringBuilder();
        switch (cons.getType()) {
            case FOREIGN_KEY -> {
                sb.append("CONSTRAINT ").append(quoteIdentifier(cons.getName())).append(" ")
                        .append("FOREIGN KEY (").append(quoteIdentifier(cons.getColumn())).append(")")
                        .append(" REFERENCES ").append(quoteIdentifier(cons.getReferencedTable()))
                        .append("(").append(quoteIdentifier(cons.getReferencedColumn())).append(")");

                if (cons.getOnDelete() != null && cons.getOnDelete() != OnDeleteAction.NO_ACTION) {
                    sb.append(" ON DELETE ").append(cons.getOnDelete().name().replace('_', ' '));
                }
                if (cons.getOnUpdate() != null && cons.getOnUpdate() != OnUpdateAction.NO_ACTION) {
                    sb.append(" ON UPDATE ").append(cons.getOnUpdate().name().replace('_', ' '));
                }
            }
            case UNIQUE -> sb.append("CONSTRAINT ").append(quoteIdentifier(cons.getName()))
                    .append(" UNIQUE (").append(quoteIdentifier(cons.getColumn())).append(")");
            case CHECK -> {
                sb.append("-- WARNING: CHECK constraints may not be enforced in some databases\n");
                sb.append("  CONSTRAINT ").append(quoteIdentifier(cons.getName()));
                if(cons.getCheckClause() != null) {
                    sb.append(" CHECK (").append(cons.getCheckClause()).append(")");
                }
            }
        }
        return sb.toString();
    }

    // --- ALTER TABLE 구문 생성 메서드 ---

    @Override
    public String getAddColumnSql(String table, ColumnModel col) {
        JavaTypeMapper.JavaType jt = getJavaTypeMapper().map(col.getJavaType());
        StringBuilder sb = new StringBuilder();

        sb.append("ALTER TABLE ").append(quoteIdentifier(table)).append(" ADD COLUMN ")
                .append(quoteIdentifier(col.getColumnName())).append(" ")
                .append(jt.getSqlType(col.getLength(), col.getPrecision(), col.getScale()));

        if (!col.isNullable()) sb.append(" NOT NULL");

        if (col.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(col.getDefaultValue(), jt));
        } else if (jt.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(jt.getDefaultValue(), jt));
        }
        sb.append(";\n");

        if (col.isUnique()) {
            String uniqueIndexName = "uk_" + table + '_' + col.getColumnName();
            sb.append("ALTER TABLE ").append(quoteIdentifier(table)).append(" ADD UNIQUE INDEX ")
                    .append(quoteIdentifier(uniqueIndexName))
                    .append(" (").append(quoteIdentifier(col.getColumnName())).append(");\n");
        }
        return sb.toString();
    }

    @Override
    public String getDropColumnSql(String table, ColumnModel col) {
        StringBuilder sb = new StringBuilder();
        if (col.isUnique()) {
            String uniqueIndexName = "uk_" + table + "_" + col.getColumnName();
            sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                    .append(" DROP INDEX ").append(quoteIdentifier(uniqueIndexName)).append(";\n");
        }
        if (col.isPrimaryKey()) {
            sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                    .append(" DROP PRIMARY KEY;\n");
        }
        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" DROP COLUMN ").append(quoteIdentifier(col.getColumnName())).append(";\n");
        return sb.toString();
    }

    @Override
    public String getModifyColumnSql(String table, ColumnModel newCol, ColumnModel oldCol) {
        StringBuilder sb = new StringBuilder();
        boolean uniqueChanged = oldCol.isUnique() != newCol.isUnique();

        if (uniqueChanged && oldCol.isUnique()) {
            String uniqueIndexName = "uk_" + table + "_" + newCol.getColumnName();
            sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                    .append(" DROP INDEX ").append(quoteIdentifier(uniqueIndexName)).append(";\n");
        }

        JavaTypeMapper.JavaType javaType = getJavaTypeMapper().map(newCol.getJavaType());
        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" MODIFY COLUMN ").append(quoteIdentifier(newCol.getColumnName())).append(" ")
                .append(javaType.getSqlType(newCol.getLength(), newCol.getPrecision(), newCol.getScale()));

        if (!newCol.isNullable()) sb.append(" NOT NULL");

        if (newCol.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(newCol.getDefaultValue(), javaType));
        } else if (javaType.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(javaType.getDefaultValue(), javaType));
        }
        sb.append(";\n");

        if (uniqueChanged && newCol.isUnique()) {
            String uniqueIndexName = "uk_" + table + "_" + newCol.getColumnName();
            sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                    .append(" ADD UNIQUE INDEX ").append(quoteIdentifier(uniqueIndexName))
                    .append(" (").append(quoteIdentifier(newCol.getColumnName())).append(");\n");
        }
        return sb.toString();
    }

    @Override
    public String getRenameColumnSql(String table, ColumnModel newCol, ColumnModel oldCol) {
        return "ALTER TABLE " + quoteIdentifier(table)
                + " RENAME COLUMN " + quoteIdentifier(oldCol.getColumnName())
                + " TO " + quoteIdentifier(newCol.getColumnName()) + ";\n";
    }

    @Override
    public String getAddConstraintSql(String table, ConstraintModel cons) {
        return getConstraintDefinitionSql(cons).replaceFirst("CONSTRAINT", "ADD CONSTRAINT") + ";\n";
    }

    @Override
    public String getDropConstraintSql(String table, ConstraintModel cons) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quoteIdentifier(table));
        switch (cons.getType()) {
            case FOREIGN_KEY -> sb.append(" DROP FOREIGN KEY ");
            case UNIQUE -> sb.append(" DROP KEY "); // DROP INDEX, DROP KEY are synonyms
            case CHECK -> sb.append(" DROP CHECK ");
            case INDEX -> sb.append(" DROP INDEX ");
        }
        sb.append(quoteIdentifier(cons.getName())).append(";\n");
        return sb.toString();
    }

    @Override
    public String getModifyConstraintSql(String table, ConstraintModel newCons, ConstraintModel oldCons) {
        String dropSql = getDropConstraintSql(table, oldCons);
        String addSql = getAddConstraintSql(table, newCons);
        return dropSql + addSql;
    }

    @Override
    public String indexStatement(IndexModel idx, String table) {
        String unique = idx.isUnique() ? "UNIQUE " : "";
        String cols = idx.getColumnNames().stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        return "CREATE " + unique + "INDEX " + quoteIdentifier(idx.getIndexName())
                + " ON " + quoteIdentifier(table) + " (" + cols + ");\n";
    }

    @Override
    public String getDropIndexSql(String table, IndexModel index) {
        return "DROP INDEX " + quoteIdentifier(index.getIndexName()) + " ON " + quoteIdentifier(table) + ";\n";
    }

    @Override
    public String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex) {
        String dropSql = getDropIndexSql(table, oldIndex);
        String addSql = indexStatement(newIndex, table);
        return dropSql + addSql;
    }

    @Override
    public String getAddPrimaryKeySql(String table, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) return "";
        String columns = pkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "ALTER TABLE " + quoteIdentifier(table) + " ADD PRIMARY KEY (" + columns + ");\n";
    }

    @Override
    public String getDropPrimaryKeySql(String table) {
        return "ALTER TABLE " + quoteIdentifier(table) + " DROP PRIMARY KEY;\n";
    }

    @Override
    public String getAddRelationshipSql(String table, RelationshipModel rel) {
        String constraintName = "fk_" + table + "_" + rel.getColumn();
        return "ALTER TABLE " + quoteIdentifier(table)
                + " ADD CONSTRAINT " + quoteIdentifier(constraintName)
                + " FOREIGN KEY (" + quoteIdentifier(rel.getColumn()) + ")"
                + " REFERENCES " + quoteIdentifier(rel.getReferencedTable())
                + " (" + quoteIdentifier(rel.getReferencedColumn()) + ");\n";
    }

    @Override
    public String getDropRelationshipSql(String table, RelationshipModel rel) {
        String constraintName = "fk_" + table + "_" + rel.getColumn();
        return "ALTER TABLE " + quoteIdentifier(table)
                + " DROP FOREIGN KEY " + quoteIdentifier(constraintName) + ";\n";
    }

    @Override
    public String getModifyRelationshipSql(String table, RelationshipModel newRel, RelationshipModel oldRel) {
        String dropSql = getDropRelationshipSql(table, oldRel);
        String addSql = getAddRelationshipSql(table, newRel);
        return dropSql + addSql;
    }
}