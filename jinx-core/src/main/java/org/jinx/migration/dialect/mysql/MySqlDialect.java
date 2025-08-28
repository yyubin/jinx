package org.jinx.migration.dialect.mysql;

import jakarta.persistence.TemporalType;
import org.jinx.migration.*;
import org.jinx.migration.contributor.create.ColumnContributor;
import org.jinx.migration.contributor.create.ConstraintContributor;
import org.jinx.migration.contributor.create.IndexContributor;
import org.jinx.migration.contributor.drop.DropTableStatementContributor;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.IdentityDialect;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.descriptor.*;
import org.jinx.model.*;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class MySqlDialect extends AbstractDialect
        implements IdentityDialect, TableGeneratorDialect {

    public MySqlDialect() {
        super();
    }

    public MySqlDialect(JavaTypeMapper javaTypeMapper, ValueTransformer valueTransformer) {
        this.javaTypeMapper = javaTypeMapper;
        this.valueTransformer = valueTransformer;
    }

    @Override
    public SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff) {
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

    // DdlDialect - Table

    @Override
    public String getCreateTableSql(EntityModel entity) {
        CreateTableBuilder builder = new CreateTableBuilder(entity.getTableName(), this);
        List<ColumnModel> cols = entity.getColumns().values().stream().toList();
        List<String> pkCols = cols.stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .toList();
        List<String> reorderedPk = MySqlUtil.reorderForIdentity(pkCols, cols);

        builder.add(new ColumnContributor(reorderedPk, cols));
        builder.add(new ConstraintContributor(entity.getConstraints().values().stream().toList()));
        builder.add(new IndexContributor(
                entity.getTableName(),
                entity.getIndexes().values().stream().toList()));

        return builder.build();
    }

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
        var visitor = new MySqlMigrationVisitor(modifiedEntity, this);
        modifiedEntity.accept(visitor, DiffResult.TableContentPhase.ALTER);
        return visitor.getAlterBuilder().build();
    }

    // Helpers for create-table builders (kept public for builder usage)

    public String openCreateTable(String table) {
        return "CREATE TABLE " + quoteIdentifier(table) + " (\n";
    }

    public String closeCreateTable() {
        return "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";
    }

    // BaseDialect

    @Override
    public String quoteIdentifier(String raw) {
        return "`" + raw + "`";
    }

    // DdlDialect - PK

    @Override
    public String getAddPrimaryKeySql(String table, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) return "";
        String columns = pkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "ALTER TABLE " + quoteIdentifier(table) + " ADD PRIMARY KEY (" + columns + ");\n";
    }

    @Override
    public String getPrimaryKeyDefinitionSql(List<String> pkColumns) {
        String cols = pkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "PRIMARY KEY (" + cols + ")";
    }

    public String getDropPrimaryKeySql(String table) {
        return "ALTER TABLE " + quoteIdentifier(table) + " DROP PRIMARY KEY;\n";
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

    // DdlDialect - Column

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
                case DATE -> sqlType = "DATE";
                case TIME -> sqlType = "TIME";
                case TIMESTAMP -> sqlType = "DATETIME";
                default -> sqlType = javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
            }
        } else {
            sqlType = javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(quoteIdentifier(c.getColumnName())).append(" ").append(sqlType);

        if (!c.isNullable()) sb.append(" NOT NULL");

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
            String uk = "uk_" + table + "_" + col.getColumnName();
            sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                    .append(" DROP INDEX ").append(quoteIdentifier(uk)).append(";\n");
        }

        if (col.isPrimaryKey()) {
            sb.append(getDropPrimaryKeySql(table));
        }

        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" DROP COLUMN ").append(quoteIdentifier(col.getColumnName()))
                .append(";\n");
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

    // DdlDialect - Constraints & Indexes

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
            case INDEX -> sb.append(indexStatement(
                    IndexModel.builder().indexName(cons.getName()).columnNames(cons.getColumns()).build(),
                    cons.getTableName()));
            case DEFAULT, NOT_NULL, AUTO -> {}
        }
        return sb.toString();
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
        return getDropConstraintSql(table, oldCons) + getAddConstraintSql(table, newCons);
    }

    @Override
    public String indexStatement(IndexModel idx, String table) {
        String unique = idx.isUnique() ? "UNIQUE " : "";
        String cols = idx.getColumnNames().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "CREATE " + unique + "INDEX " + quoteIdentifier(idx.getIndexName())
                + " ON " + quoteIdentifier(table) + " (" + cols + ");\n";
    }

    @Override
    public String getDropIndexSql(String table, IndexModel index) {
        return "DROP INDEX " + quoteIdentifier(index.getIndexName()) + " ON " + quoteIdentifier(table) + ";\n";
    }

    @Override
    public String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex) {
        return getDropIndexSql(table, oldIndex) + indexStatement(newIndex, table);
    }

    // DdlDialect - Relationships

    @Override
    public String getAddRelationshipSql(String table, RelationshipModel rel) {
        // 제약 조건 이름 생성: 복합 컬럼을 고려
        String constraintName = rel.getConstraintName() != null ? rel.getConstraintName() :
                "fk_" + table + "_" + String.join("_", rel.getColumns() != null ? rel.getColumns() : List.of());
        StringBuilder sb = new StringBuilder();

        // 복합 외래 키 컬럼 처리
        String fkColumns = rel.getColumns() != null
                ? rel.getColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(","))
                : "";
        String referencedColumns = rel.getReferencedColumns() != null
                ? rel.getReferencedColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(","))
                : "";

        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" ADD CONSTRAINT ").append(quoteIdentifier(constraintName))
                .append(" FOREIGN KEY (").append(fkColumns).append(")")
                .append(" REFERENCES ").append(quoteIdentifier(rel.getReferencedTable()))
                .append(" (").append(referencedColumns).append(")");

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
        // 제약 조건 이름 생성: 복합 컬럼을 고려
        String constraintName = rel.getConstraintName() != null ? rel.getConstraintName() :
                "fk_" + table + "_" + String.join("_", rel.getColumns() != null ? rel.getColumns() : List.of());
        return "ALTER TABLE " + quoteIdentifier(table)
                + " DROP FOREIGN KEY " + quoteIdentifier(constraintName) + ";\n";
    }

    @Override
    public String getModifyRelationshipSql(String table, RelationshipModel newRel, RelationshipModel oldRel) {
        return getDropRelationshipSql(table, oldRel) + getAddRelationshipSql(table, newRel);
    }

    // IdentityDialect
    @Override
    public String getIdentityClause(ColumnModel c) {
        return " AUTO_INCREMENT";
    }

    // TableGeneratorDialect
    @Override
    public String getCreateTableGeneratorSql(TableGeneratorModel tg) {
        StringBuilder ddl = new StringBuilder();
        var stringType = getJavaTypeMapper().map("java.lang.String");
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
                .append(" VALUES (")
                .append(valueTransformer.quote(tg.getPkColumnValue(), stringType))
                .append(", ").append(tg.getInitialValue())
                .append(");\n");
        return ddl.toString();
    }

    @Override
    public String getDropTableGeneratorSql(TableGeneratorModel tg) {
        return "DROP TABLE IF EXISTS " + quoteIdentifier(tg.getTable()) + ";\n";
    }

    @Override
    public String getAlterTableGeneratorSql(TableGeneratorModel newTg, TableGeneratorModel oldTg) {
        return "";
    }

    // Liquibase helper
    public String getLiquibaseTypeName(ColumnModel column) {
        String javaType = column.getJavaType();
        int length = column.getLength();
        int precision = column.getPrecision();
        int scale = column.getScale();

        if (column.isLob()) {
            if (javaType.equals("java.lang.String")) return "TEXT";
            return "BLOB";
        }

        return switch (javaType) {
            case "java.lang.String" -> "VARCHAR(" + length + ")";
            case "int", "java.lang.Integer" -> "INT";
            case "long", "java.lang.Long" -> "BIGINT";
            case "double", "java.lang.Double" -> "DOUBLE";
            case "float", "java.lang.Float" -> "FLOAT";
            case "java.math.BigDecimal" -> "DECIMAL(" + precision + "," + scale + ")";
            case "java.math.BigInteger" -> "NUMERIC(" + precision + ")";
            case "boolean", "java.lang.Boolean" -> "BOOLEAN";
            case "java.time.LocalDate" -> "DATE";
            case "java.time.LocalDateTime" -> "DATETIME";
            case "java.time.OffsetDateTime", "java.time.ZonedDateTime" -> "TIMESTAMP";
            case "java.util.Date" -> {
                if (column.getTemporalType() == TemporalType.DATE) yield "DATE";
                if (column.getTemporalType() == TemporalType.TIME) yield "TIME";
                yield "DATETIME";
            }
            case "byte[]" -> "VARBINARY(" + length + ")";
            case "java.util.UUID" -> "CHAR(36)";
            case "java.lang.Enum" -> column.isEnumStringMapping() ? "VARCHAR(" + length + ")" : "INT";
            default -> "VARCHAR(" + length + ")";
        };
    }
}
