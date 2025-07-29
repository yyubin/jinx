package org.jinx.migration.dialect.mysql;

import org.jinx.migration.*;
import org.jinx.migration.internal.create.ColumnContributor;
import org.jinx.migration.internal.create.ConstraintContributor;
import org.jinx.migration.internal.create.IndexContributor;
import org.jinx.migration.internal.drop.DropTableStatementContributor;
import org.jinx.model.*;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class MySqlDialect extends AbstractDialect {

    public MySqlDialect() {
        super();
    }

    // 테스트용 생성자
    public MySqlDialect(JavaTypeMapper javaTypeMapper, ValueTransformer valueTransformer) {
        this.javaTypeMapper = javaTypeMapper;
        this.valueTransformer = valueTransformer;
    }

    @Override
    public MigrationVisitor createVisitor(DiffResult.ModifiedEntity diff) {
        return new MySqlMigrationVisitor(diff, this);
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
        CreateTableBuilder builder = new CreateTableBuilder(entity.getTableName(), this);
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

        return builder.build();
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
    public String getRenameTableSql(String oldTableName, String newTableName) {
        return "ALTER TABLE " + quoteIdentifier(oldTableName) + " RENAME TO " + quoteIdentifier(newTableName) + ";\n";
    }

    @Override
    public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) {
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
    public String getPrimaryKeyDefinitionSql(List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) return "";
        // AUTO_INCREMENT 컬럼이 첫 번째로 오도록 재배치
        List<String> reorderedPkColumns = reorderPkColumnsForAutoIncrement(pkColumns);
        return "PRIMARY KEY (" + reorderedPkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
    }

    private List<String> reorderPkColumnsForAutoIncrement(List<String> pkColumns) {
        List<String> reordered = new java.util.ArrayList<>(pkColumns);
        // MySQL에서는 AUTO_INCREMENT 컬럼이 복합 PK의 첫 번째 컬럼이어야 함
        for (int i = 0; i < reordered.size(); i++) {
            if (columnIsIdentity(reordered.get(i))) {
                String autoIncrementCol = reordered.remove(i);
                reordered.add(0, autoIncrementCol);
                break;
            }
        }
        return reordered;
    }

    // MySqlMigrationVisitor에서 제공된 pkColumns 사용
    protected boolean columnIsIdentity(String colName) {
        // MySqlMigrationVisitor에서 pkColumns를 통해 확인
        // 실제 구현에서는 visitor가 제공한 컬럼 목록 사용
        return false; // Placeholder, visitor에서 컬럼 정보 제공
    }

    @Override
    public String getColumnDefinitionSql(ColumnModel c) {
        String javaType = c.getConversionClass() != null ? c.getConversionClass() : c.getJavaType();
        JavaTypeMapper.JavaType javaTypeMapped = javaTypeMapper.map(javaType);

        String sqlType;
        if (c.isLob()) {
            sqlType = c.getJavaType().equals("java.lang.String") ? "TEXT" : "BLOB";
        } else if (c.isVersion()) {
            sqlType = c.getJavaType().equals("java.lang.Long") ? "BIGINT" : "TIMESTAMP";
        } else if (c.getTemporalType() != null) {
            switch (c.getTemporalType()) {
                case DATE: sqlType = "DATE"; break;
                case TIME: sqlType = "TIME"; break;
                case TIMESTAMP: sqlType = "DATETIME"; break;
                default: sqlType = javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
            }
        } else {
            sqlType = javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(quoteIdentifier(c.getColumnName())).append(" ").append(sqlType);

        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (c.getGenerationStrategy() == GenerationStrategy.IDENTITY) {
            sb.append(getIdentityClause(c));
        } else if (c.isManualPrimaryKey()) {
            sb.append(" PRIMARY KEY");
        }

        if (c.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(c.getDefaultValue(), javaTypeMapped));
        } else if (javaTypeMapped.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(valueTransformer.quote(javaTypeMapped.getDefaultValue(), javaTypeMapped));
        }

        return sb.toString();
    }

    @Override
    public String getConstraintDefinitionSql(ConstraintModel cons) {
        StringBuilder sb = new StringBuilder();
        switch (cons.getType()) {
            case UNIQUE -> sb.append("CONSTRAINT ").append(quoteIdentifier(cons.getName()))
                    .append(" UNIQUE (")
                    .append(cons.getColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")))
                    .append(")");
            case CHECK -> {
                sb.append("-- WARNING: CHECK constraints may not be enforced in some databases\n");
                sb.append("CONSTRAINT ").append(quoteIdentifier(cons.getName()));
                if (cons.getCheckClause() != null) {
                    sb.append(" CHECK (").append(cons.getCheckClause()).append(")");
                }
            }
            case PRIMARY_KEY -> sb.append(getPrimaryKeyDefinitionSql(cons.getColumns()));
            case INDEX -> sb.append(indexStatement(IndexModel.builder()
                    .indexName(cons.getName())
                    .columnNames(cons.getColumns())
                    .build(), cons.getTableName()));
            case DEFAULT, NOT_NULL, AUTO -> {} // MySQL에서는 별도 처리 불필요
        }
        return sb.toString();
    }

    @Override
    public String getDropPrimaryKeySql(String table) {
        throw new UnsupportedOperationException("MySQL does not support dropping primary keys with currentColumns, use getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns)");
    }

    @Override
    public String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns) {
        StringBuilder sb = new StringBuilder();
        for (ColumnModel col : currentColumns) {
            if (col.isPrimaryKey() && col.getGenerationStrategy() == GenerationStrategy.IDENTITY) {
                JavaTypeMapper.JavaType javaType = getJavaTypeMapper().map(col.getJavaType());
                sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                        .append(" MODIFY COLUMN ").append(quoteIdentifier(col.getColumnName())).append(" ")
                        .append(javaType.getSqlType(col.getLength(), col.getPrecision(), col.getScale()));
                if (!col.isNullable()) sb.append(" NOT NULL");
                if (col.getDefaultValue() != null) {
                    sb.append(" DEFAULT ").append(getValueTransformer().quote(col.getDefaultValue(), javaType));
                }
                sb.append(";\n");
            }
        }
        return sb.append("ALTER TABLE ").append(quoteIdentifier(table)).append(" DROP PRIMARY KEY;\n").toString();
    }

    // --- ALTER TABLE 구문 생성 메서드 ---

    @Override
    public String getAddColumnSql(String table, ColumnModel col) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(quoteIdentifier(table)).append(" ADD COLUMN ")
                .append(getColumnDefinitionSql(col)).append(";\n");

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
            sb.append(getDropPrimaryKeySql(table));
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

        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" MODIFY COLUMN ").append(getColumnDefinitionSql(newCol)).append(";\n");

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
            case UNIQUE -> sb.append(" DROP KEY ");
            case CHECK -> sb.append(" DROP CHECK ");
            case PRIMARY_KEY -> sb.append(" DROP PRIMARY KEY ");
            case INDEX -> sb.append(" DROP INDEX ");
            default -> sb.append(" DROP CONSTRAINT ");
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
        // AUTO_INCREMENT 컬럼이 첫 번째로 오도록 재배치
        List<String> reorderedPkColumns = reorderPkColumnsForAutoIncrement(pkColumns);
        String columns = reorderedPkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "ALTER TABLE " + quoteIdentifier(table) + " ADD PRIMARY KEY (" + columns + ");\n";
    }

    @Override
    public String getAddRelationshipSql(String table, RelationshipModel rel) {
        String constraintName = rel.getConstraintName() != null ? rel.getConstraintName() : "fk_" + table + "_" + rel.getColumn();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" ADD CONSTRAINT ").append(quoteIdentifier(constraintName))
                .append(" FOREIGN KEY (").append(quoteIdentifier(rel.getColumn())).append(")")
                .append(" REFERENCES ").append(quoteIdentifier(rel.getReferencedTable()))
                .append(" (").append(quoteIdentifier(rel.getReferencedColumn())).append(")");

        if (rel.getOnDelete() != null && rel.getOnDelete() != OnDeleteAction.NO_ACTION) {
            sb.append(" ON DELETE ").append(rel.getOnDelete().name().replace('_', ' '));
        }
        if (rel.getOnUpdate() != null && rel.getOnUpdate() != OnUpdateAction.NO_ACTION) {
            sb.append(" ON UPDATE ").append(rel.getOnUpdate().name().replace('_', ' '));
        }
        sb.append(";\n");
        return sb.toString();
    }

    @Override
    public String getDropRelationshipSql(String table, RelationshipModel rel) {
        String constraintName = rel.getConstraintName() != null ? rel.getConstraintName() : "fk_" + table + "_" + rel.getColumn();
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