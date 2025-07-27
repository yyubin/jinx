package org.jinx.migration.internal;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorType;
import org.jinx.migration.Dialect;
import org.jinx.migration.DiffResult;
import org.jinx.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MySQLDialect implements Dialect {
    @Override
    public String getCreateTableSql(EntityModel entity) {
        StringBuilder sql = new StringBuilder("CREATE TABLE `")
                .append(entity.getTableName()).append("` (\n");

        List<String> pk = new ArrayList<>();

        for (ColumnModel c : entity.getColumns().values()) {
            sql.append("  `").append(c.getColumnName()).append("` ")
                    .append(c.getSqlType());
            if (!c.isNullable()) sql.append(" NOT NULL");
            if (c.getDefaultValue() != null) {
                sql.append(" DEFAULT ").append(quoteDefaultValue(c.getDefaultValue(), c.getJavaType()));
            }
            sql.append(",\n");
            if (c.isPrimaryKey()) pk.add("`" + c.getColumnName() + "`");
        }

        for (ConstraintModel cons : entity.getConstraints()) {
            if (cons.getName() == null || cons.getName().isEmpty()) {
                throw new IllegalStateException("Constraint name must not be null or empty for " + cons.getColumn());
            }
            switch (cons.getType()) {
                case FOREIGN_KEY -> {
                    sql.append("  CONSTRAINT `").append(cons.getName()).append("` ")
                            .append("FOREIGN KEY (`").append(cons.getColumn()).append("`)")
                            .append(" REFERENCES `").append(cons.getReferencedTable()).append("`")
                            .append("(`").append(cons.getReferencedColumn()).append("`)");
                    if (cons.getOnDelete() != null && cons.getOnDelete() != OnDeleteAction.NO_ACTION)
                        sql.append(" ON DELETE ").append(cons.getOnDelete());
                    if (cons.getOnUpdate() != null && cons.getOnUpdate() != OnUpdateAction.NO_ACTION)
                        sql.append(" ON UPDATE ").append(cons.getOnUpdate());
                    sql.append(",\n");
                }
                case UNIQUE -> sql.append("  CONSTRAINT `").append(cons.getName())
                        .append("` UNIQUE (`").append(cons.getColumn()).append("`),\n");
                case CHECK -> {
                    sql.append("  -- WARNING: CHECK constraints may not be enforced in MySQL < 8.0.16\n");
                    sql.append("  CONSTRAINT `").append(cons.getName())
                            .append("` CHECK (").append(cons.getName()).append("),\n");
                }
            }
        }

        if (!pk.isEmpty())
            sql.append("  PRIMARY KEY (").append(String.join(", ", pk)).append(")\n");

        // Trailing comma 제거
        int last = sql.lastIndexOf(",\n");
        if (last != -1) sql.delete(last, last + 2);
        sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");

        // Index
        for (IndexModel idx : entity.getIndexes().values()) {
            sql.append(idx.isUnique() ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
                    .append("`").append(idx.getIndexName()).append("` ON `")
                    .append(entity.getTableName()).append("` (")
                    .append(String.join(", ",
                            idx.getColumnNames().stream().map(n -> "`" + n + "`").toList()))
                    .append(");\n");
        }
        return sql.toString();
    }

    @Override
    public String getDropTableSql(EntityModel entity) {
        return "DROP TABLE IF EXISTS `" + entity.getTableName() + "`;\n";
    }

    @Override
    public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) {
        StringBuilder sql = new StringBuilder();
        String tableName = modifiedEntity.getNewEntity().getTableName();
        List<String> pkColumns = modifiedEntity.getNewEntity().getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .map(n -> "`" + n + "`")
                .toList();
        boolean hasPrimaryKey = !pkColumns.isEmpty();

        // Added Columns
        for (DiffResult.ColumnDiff diff : modifiedEntity.getColumnDiffs()) {
            if (diff.getType() == DiffResult.ColumnDiff.Type.ADDED) {
                ColumnModel col = diff.getColumn();
                sql.append("ALTER TABLE `").append(tableName)
                        .append("` ADD `").append(col.getColumnName()).append("` ").append(col.getSqlType());
                if (!col.isNullable()) sql.append(" NOT NULL");
                if (col.getDefaultValue() != null) {
                    sql.append(" DEFAULT ").append(quoteDefaultValue(col.getDefaultValue(), col.getJavaType()));
                }
                sql.append(";\n");
                if (col.isUnique()) {
                    String uniqueIndexName = "uk_" + tableName + "_" + col.getColumnName();
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` ADD UNIQUE INDEX `").append(uniqueIndexName)
                            .append("` (`").append(col.getColumnName()).append("`);\n");
                }
            }
        }

        // Dropped Columns
        for (DiffResult.ColumnDiff diff : modifiedEntity.getColumnDiffs()) {
            if (diff.getType() == DiffResult.ColumnDiff.Type.DROPPED) {
                ColumnModel col = diff.getColumn();
                if (col.isUnique()) {
                    String uniqueIndexName = "uk_" + tableName + "_" + col.getColumnName();
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` DROP INDEX `").append(uniqueIndexName).append("`;\n");
                }
                if (col.isPrimaryKey() && hasPrimaryKey) {
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` DROP PRIMARY KEY;\n");
                }
                sql.append("ALTER TABLE `").append(tableName)
                        .append("` DROP COLUMN `").append(col.getColumnName()).append("`;\n");
            }
        }

        // Modified Columns
        for (DiffResult.ColumnDiff diff : modifiedEntity.getColumnDiffs()) {
            if (diff.getType() == DiffResult.ColumnDiff.Type.MODIFIED) {
                ColumnModel newCol = diff.getColumn();
                ColumnModel oldCol = diff.getOldColumn();
                if (oldCol == null) {
                    throw new IllegalStateException("Old column missing for modified column: " + newCol.getColumnName());
                }

                boolean uniqueChanged = oldCol.isUnique() != newCol.isUnique();
                boolean pkChanged = oldCol.isPrimaryKey() != newCol.isPrimaryKey();
                boolean typeOrNullChanged = !Objects.equals(oldCol.getSqlType(), newCol.getSqlType()) ||
                        oldCol.isNullable() != newCol.isNullable();

                if (pkChanged || typeOrNullChanged) {
                    if (hasPrimaryKey && oldCol.isPrimaryKey()) {
                        sql.append("ALTER TABLE `").append(tableName)
                                .append("` DROP PRIMARY KEY;\n");
                    }
                }

                if (uniqueChanged && oldCol.isUnique()) {
                    String uniqueIndexName = "uk_" + tableName + "_" + newCol.getColumnName();
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` DROP INDEX `").append(uniqueIndexName).append("`;\n");
                }

                sql.append("ALTER TABLE `").append(tableName)
                        .append("` MODIFY `").append(newCol.getColumnName()).append("` ").append(newCol.getSqlType());
                if (!newCol.isNullable()) sql.append(" NOT NULL");
                if (newCol.getDefaultValue() != null) {
                    sql.append(" DEFAULT ").append(quoteDefaultValue(newCol.getDefaultValue(), newCol.getJavaType()));
                }
                sql.append(";\n");

                if (uniqueChanged && newCol.isUnique()) {
                    String uniqueIndexName = "uk_" + tableName + "_" + newCol.getColumnName();
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` ADD UNIQUE INDEX `").append(uniqueIndexName)
                            .append("` (`").append(newCol.getColumnName()).append("`);\n");
                }

                if ((pkChanged || typeOrNullChanged) && newCol.isPrimaryKey() && hasPrimaryKey) {
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` ADD PRIMARY KEY (").append(String.join(", ", pkColumns)).append(");\n");
                }
            }
        }

        // Added Indexes
        for (DiffResult.IndexDiff diff : modifiedEntity.getIndexDiffs()) {
            if (diff.getType() == DiffResult.IndexDiff.Type.ADDED) {
                IndexModel index = diff.getIndex();
                sql.append(index.isUnique() ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
                        .append("`").append(index.getIndexName()).append("` ON `").append(tableName)
                        .append("` (").append(String.join(", ",
                                index.getColumnNames().stream().map(n -> "`" + n + "`").toList())).append(");\n");
            }
        }

        // Dropped Indexes
        for (DiffResult.IndexDiff diff : modifiedEntity.getIndexDiffs()) {
            if (diff.getType() == DiffResult.IndexDiff.Type.DROPPED) {
                sql.append("ALTER TABLE `").append(tableName)
                        .append("` DROP INDEX `").append(diff.getIndex().getIndexName()).append("`;\n");
            }
        }

        // Added Constraints
        for (DiffResult.ConstraintDiff diff : modifiedEntity.getConstraintDiffs()) {
            if (diff.getType() == DiffResult.ConstraintDiff.Type.ADDED) {
                ConstraintModel cons = diff.getConstraint();
                if (cons.getType() == ConstraintType.FOREIGN_KEY) {
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` ADD CONSTRAINT `").append(cons.getName())
                            .append("` FOREIGN KEY (`").append(cons.getColumn())
                            .append("`) REFERENCES `").append(cons.getReferencedTable())
                            .append("` (`").append(cons.getReferencedColumn()).append("`)");
                    if (cons.getOnDelete() != null && cons.getOnDelete() != OnDeleteAction.NO_ACTION) {
                        sql.append(" ON DELETE ").append(cons.getOnDelete());
                    }
                    if (cons.getOnUpdate() != null && cons.getOnUpdate() != OnUpdateAction.NO_ACTION) {
                        sql.append(" ON UPDATE ").append(cons.getOnUpdate());
                    }
                    sql.append(";\n");
                } else if (cons.getType() == ConstraintType.UNIQUE) {
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` ADD CONSTRAINT `").append(cons.getName())
                            .append("` UNIQUE (`").append(cons.getColumn()).append("`);\n");
                } else if (cons.getType() == ConstraintType.CHECK) {
                    sql.append("ALTER TABLE `").append(tableName)
                            .append("` ADD CONSTRAINT `").append(cons.getName())
                            .append("` CHECK (").append(cons.getName()).append(");\n");
                    sql.append("  -- WARNING: CHECK constraints may not be enforced in MySQL < 8.0.16\n");
                }
            }
        }

        // Dropped Constraints
        for (DiffResult.ConstraintDiff diff : modifiedEntity.getConstraintDiffs()) {
            if (diff.getType() == DiffResult.ConstraintDiff.Type.DROPPED) {
                ConstraintModel c = diff.getConstraint();
                switch (c.getType()) {
                    case FOREIGN_KEY -> sql.append("ALTER TABLE `").append(tableName)
                            .append("` DROP FOREIGN KEY `").append(c.getName()).append("`;\n");
                    case UNIQUE, INDEX -> sql.append("ALTER TABLE `").append(tableName)
                            .append("` DROP INDEX `").append(c.getName()).append("`;\n");
                    case CHECK -> {
                        sql.append("ALTER TABLE `").append(tableName)
                                .append("` DROP CHECK `").append(c.getName()).append("`;\n");
                        sql.append("  -- WARNING: DROP CHECK not supported in MySQL < 8.0.16\n");
                    }
                }
            }
        }

        return sql.toString().trim();
    }

    private String quoteDefaultValue(String defaultValue, String javaType) {
        if (defaultValue == null) return null;
        // 문자열, 날짜, 시간 타입은 인용부호 필요
        if (javaType.equals("java.lang.String") ||
                javaType.equals("java.time.LocalDate") ||
                javaType.equals("java.time.LocalDateTime")) {
            return "'" + defaultValue + "'";
        }
        return defaultValue;
    }

    public static String mapJavaTypeToSqlType(String javaType, Column column) {
        switch (javaType) {
            case "int":
            case "java.lang.Integer":
                return "INT";
            case "long":
            case "java.lang.Long":
                return "BIGINT";
            case "java.lang.String":
                int length = column.length() > 0 ? column.length() : 255;
                return "VARCHAR(" + length + ")";
            case "double":
            case "java.lang.Double":
                return "DOUBLE";
            case "float":
            case "java.lang.Float":
                return "FLOAT";
            case "java.math.BigDecimal":
                int precision = column.precision() > 0 ? column.precision() : 10;
                int scale = column.scale() > 0 ? column.scale() : 2;
                return "DECIMAL(" + precision + "," + scale + ")";
            case "boolean":
            case "java.lang.Boolean":
                return "TINYINT(1)";
            case "java.time.LocalDate":
                return "DATE";
            case "java.time.LocalDateTime":
                return "TIMESTAMP(6)"; // 마이크로초 지원
            case "java.math.BigInteger":
                return "BIGINT";
            default:
                return "TEXT";
        }
    }

    public static String mapDiscriminatorType(DiscriminatorType discriminatorType) {
        switch (discriminatorType) {
            case STRING:
                return "VARCHAR(255)";
            case CHAR:
                return "CHAR(1)";
            case INTEGER:
                return "INT";
            default:
                return "VARCHAR(255)";
        }
    }
}