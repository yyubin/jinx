package org.jinx.migration.dialect.postgresql;

import jakarta.persistence.TemporalType;
import org.jinx.migration.AbstractDialect;
import org.jinx.migration.CreateTableBuilder;
import org.jinx.migration.contributor.create.ColumnContributor;
import org.jinx.migration.contributor.create.ConstraintContributor;
import org.jinx.migration.contributor.create.IndexContributor;
import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.IdentityDialect;
import org.jinx.migration.spi.dialect.LiquibaseDialect;
import org.jinx.migration.spi.dialect.SequenceDialect;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.*;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PostgreSqlDialect extends AbstractDialect
        implements IdentityDialect, SequenceDialect, TableGeneratorDialect, LiquibaseDialect {

    public PostgreSqlDialect() {
        super();
    }

    public PostgreSqlDialect(JavaTypeMapper javaTypeMapper, ValueTransformer valueTransformer) {
        this.javaTypeMapper = javaTypeMapper;
        this.valueTransformer = valueTransformer;
    }

    @Override
    public SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff) {
        return new PostgreSqlMigrationVisitor(diff, this);
    }

    @Override
    protected JavaTypeMapper initializeJavaTypeMapper() {
        return new PostgreSqlJavaTypeMapper();
    }

    @Override
    protected ValueTransformer initializeValueTransformer() {
        return new PostgreSqlValueTransformer();
    }

    @Override
    public JavaTypeMapper getJavaTypeMapper() {
        return javaTypeMapper;
    }

    // ── BaseDialect ──────────────────────────────────────────────────────────

    @Override
    public String quoteIdentifier(String raw) {
        return "\"" + raw + "\"";
    }

    // ── DdlDialect · Table ───────────────────────────────────────────────────

    @Override
    public String openCreateTable(String table) {
        return "CREATE TABLE " + quoteIdentifier(table) + " (\n";
    }

    @Override
    public String closeCreateTable() {
        return "\n);";
    }

    @Override
    public String getCreateTableSql(EntityModel entity) {
        CreateTableBuilder builder = new CreateTableBuilder(entity.getTableName(), this);
        List<ColumnModel> cols = entity.getColumns().values().stream().toList();
        List<String> pkCols = cols.stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .toList();

        builder.add(new ColumnContributor(pkCols, cols));
        builder.add(new ConstraintContributor(entity.getConstraints().values().stream().toList()));
        builder.add(new IndexContributor(
                entity.getTableName(),
                entity.getIndexes().values().stream().toList()));

        return builder.build();
    }

    @Override
    public String getDropTableSql(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteIdentifier(tableName) + ";\n";
    }

    @Override
    public String getRenameTableSql(String oldTableName, String newTableName) {
        return "ALTER TABLE " + quoteIdentifier(oldTableName)
                + " RENAME TO " + quoteIdentifier(newTableName) + ";\n";
    }

    @Override
    public String getAlterTableSql(DiffResult.ModifiedEntity modifiedEntity) {
        var visitor = new PostgreSqlMigrationVisitor(modifiedEntity, this);
        modifiedEntity.accept(visitor, DiffResult.TableContentPhase.ALTER);
        return visitor.getAlterBuilder().build();
    }

    // ── DdlDialect · PK ─────────────────────────────────────────────────────

    @Override
    public String getPrimaryKeyDefinitionSql(List<String> pkColumns) {
        String cols = pkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "PRIMARY KEY (" + cols + ")";
    }

    @Override
    public String getAddPrimaryKeySql(String table, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) return "";
        String columns = pkColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "ALTER TABLE " + quoteIdentifier(table)
                + " ADD PRIMARY KEY (" + columns + ");\n";
    }

    /**
     * PK 제약을 DROP합니다.
     *
     * <p><b>제한사항:</b> 이 메서드는 PK 제약 이름을 알 수 없으므로
     * PostgreSQL 기본 명명 규칙({@code {table}_pkey})으로 fallback합니다.
     * 커스텀 PK 제약 이름({@code CONSTRAINT my_pk PRIMARY KEY})을 사용한 경우
     * 이 구문은 실패합니다.
     *
     * <p>ALTER TABLE 경로(스키마 diff)에서는 {@link PostgreSqlMigrationVisitor}가
     * EntityModel의 constraints 맵에서 실제 이름을 추출하여
     * {@link PostgreSqlPrimaryKeyDropContributor}를 사용하므로 이 제한이 적용되지 않습니다.
     */
    @Override
    public String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns) {
        String pkConstraint = PostgreSqlUtil.defaultPkConstraintName(table);
        return "ALTER TABLE " + quoteIdentifier(table)
                + " DROP CONSTRAINT " + quoteIdentifier(pkConstraint) + ";\n";
    }

    // ── DdlDialect · Column ──────────────────────────────────────────────────

    @Override
    public String getColumnDefinitionSql(ColumnModel c) {
        String typeKey = c.getConverterOutputType() != null
                ? c.getConverterOutputType()
                : c.getJavaType();
        JavaTypeMapper.JavaType javaTypeMapped = javaTypeMapper.map(typeKey);

        boolean overrideContainsIdentity = false;
        boolean overrideContainsNotNull  = false;
        boolean overrideContainsDefault  = false;
        boolean overrideContainsPrimaryKey = false;

        String sqlType;
        if (c.getSqlTypeOverride() != null && !c.getSqlTypeOverride().trim().isEmpty()) {
            String override = c.getSqlTypeOverride().trim();
            overrideContainsIdentity   = override.matches("(?i).*\\bgenerated\\b.*");
            overrideContainsNotNull    = override.matches("(?i).*\\bnot\\s+null\\b.*");
            overrideContainsDefault    = override.matches("(?i).*\\bdefault\\b.*");
            overrideContainsPrimaryKey = override.matches("(?i).*\\bprimary\\s+key\\b.*");
            sqlType = override;
        } else if (c.isLob()) {
            sqlType = c.getJavaType().equals("java.lang.String") ? "TEXT" : "BYTEA";
        } else if (c.isVersion()) {
            sqlType = c.getJavaType().equals("java.lang.Long") ? "BIGINT" : "TIMESTAMP";
        } else if (c.getTemporalType() != null) {
            sqlType = switch (c.getTemporalType()) {
                case DATE      -> "DATE";
                case TIME      -> "TIME";
                case TIMESTAMP -> "TIMESTAMP";
                default        -> javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
            };
        } else if (c.getEnumValues() != null && c.getEnumValues().length > 0) {
            // PG enum types require a CREATE TYPE step; use VARCHAR/INTEGER for portability
            sqlType = c.isEnumStringMapping()
                    ? "VARCHAR(" + (c.getLength() > 0 ? c.getLength() : 255) + ")"
                    : "INTEGER";
        } else {
            sqlType = javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
        }

        boolean isIdentityLike = overrideContainsIdentity || shouldUseAutoIncrement(c.getGenerationStrategy());

        StringBuilder sb = new StringBuilder();
        sb.append(quoteIdentifier(c.getColumnName())).append(" ").append(sqlType);

        if ((!c.isNullable() || isIdentityLike) && !overrideContainsNotNull) {
            sb.append(" NOT NULL");
        }

        if (shouldUseAutoIncrement(c.getGenerationStrategy()) && !overrideContainsIdentity) {
            sb.append(getIdentityClause(c));
        }

        if (c.isManualPrimaryKey() && !overrideContainsPrimaryKey) {
            sb.append(" PRIMARY KEY");
        }

        if (!isIdentityLike && !c.isLob() && !overrideContainsDefault) {
            if (c.getDefaultValue() != null) {
                sb.append(" DEFAULT ").append(valueTransformer.quote(c.getDefaultValue(), javaTypeMapped));
            } else if (c.getGenerationStrategy() == GenerationStrategy.UUID && getUuidDefaultValue() != null) {
                sb.append(" DEFAULT ").append(getUuidDefaultValue());
            } else if (javaTypeMapped.getDefaultValue() != null) {
                sb.append(" DEFAULT ").append(valueTransformer.quote(javaTypeMapped.getDefaultValue(), javaTypeMapped));
            }
        }

        return sb.toString();
    }

    @Override
    public String getAddColumnSql(String table, ColumnModel col) {
        return "ALTER TABLE " + quoteIdentifier(table) + " ADD COLUMN "
                + getColumnDefinitionSql(col) + ";\n";
    }

    @Override
    public String getDropColumnSql(String table, ColumnModel col) {
        StringBuilder sb = new StringBuilder();
        if (col.isPrimaryKey()) {
            sb.append(getDropPrimaryKeySql(table, List.of(col)));
        }
        sb.append("ALTER TABLE ").append(quoteIdentifier(table))
                .append(" DROP COLUMN ").append(quoteIdentifier(col.getColumnName()))
                .append(";\n");
        return sb.toString();
    }

    /**
     * PG ALTER COLUMN은 타입·nullable·default를 각각 별도 구문으로 분리 생성합니다.
     *
     * <p><b>알려진 제한사항:</b> {@code GenerationStrategy}가 IDENTITY ↔ NONE/SEQUENCE 사이로
     * 변경될 때 {@code GENERATED BY DEFAULT AS IDENTITY} 절의 추가·제거는 생성하지 않습니다.
     * PG에서 identity 해제는 {@code ALTER COLUMN col DROP IDENTITY IF EXISTS}가 필요하며,
     * 이 케이스는 향후 별도 contributor로 지원할 예정입니다.
     */
    @Override
    public String getModifyColumnSql(String table, ColumnModel newCol, ColumnModel oldCol) {
        StringBuilder sb = new StringBuilder();
        String tableQ = quoteIdentifier(table);
        String colQ   = quoteIdentifier(newCol.getColumnName());

        String newSqlType = resolveColumnSqlType(newCol);
        String oldSqlType = resolveColumnSqlType(oldCol);
        if (!newSqlType.equalsIgnoreCase(oldSqlType)) {
            sb.append("ALTER TABLE ").append(tableQ)
              .append(" ALTER COLUMN ").append(colQ)
              .append(" TYPE ").append(newSqlType).append(";\n");
        }

        boolean newIdentity = shouldUseAutoIncrement(newCol.getGenerationStrategy());

        if (newCol.isNullable() != oldCol.isNullable() && !newIdentity) {
            if (!newCol.isNullable()) {
                sb.append("ALTER TABLE ").append(tableQ)
                  .append(" ALTER COLUMN ").append(colQ)
                  .append(" SET NOT NULL;\n");
            } else {
                sb.append("ALTER TABLE ").append(tableQ)
                  .append(" ALTER COLUMN ").append(colQ)
                  .append(" DROP NOT NULL;\n");
            }
        }

        if (!newIdentity) {
            String newDef = resolveDefaultExpression(newCol);
            String oldDef = resolveDefaultExpression(oldCol);
            if (!Objects.equals(newDef, oldDef)) {
                if (newDef != null) {
                    sb.append("ALTER TABLE ").append(tableQ)
                      .append(" ALTER COLUMN ").append(colQ)
                      .append(" SET DEFAULT ").append(newDef).append(";\n");
                } else {
                    sb.append("ALTER TABLE ").append(tableQ)
                      .append(" ALTER COLUMN ").append(colQ)
                      .append(" DROP DEFAULT;\n");
                }
            }
        }

        return sb.toString();
    }

    @Override
    public String getRenameColumnSql(String table, ColumnModel newCol, ColumnModel oldCol) {
        return "ALTER TABLE " + quoteIdentifier(table)
                + " RENAME COLUMN " + quoteIdentifier(oldCol.getColumnName())
                + " TO " + quoteIdentifier(newCol.getColumnName()) + ";\n";
    }

    // ── DdlDialect · Constraints & Indexes ──────────────────────────────────

    @Override
    public String getConstraintDefinitionSql(ConstraintModel cons) {
        return switch (cons.getType()) {
            case UNIQUE -> "CONSTRAINT " + quoteIdentifier(cons.getName())
                    + " UNIQUE ("
                    + cons.getColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "))
                    + ")";
            case CHECK -> "CONSTRAINT " + quoteIdentifier(cons.getName())
                    + (cons.getCheckClause() != null ? " CHECK (" + cons.getCheckClause() + ")" : "");
            case PRIMARY_KEY -> getPrimaryKeyDefinitionSql(cons.getColumns());
            case INDEX -> indexStatement(
                    IndexModel.builder().indexName(cons.getName()).columnNames(cons.getColumns()).build(),
                    cons.getTableName());
            default -> "";
        };
    }

    @Override
    public String getAddConstraintSql(String table, ConstraintModel cons) {
        return switch (cons.getType()) {
            case UNIQUE -> "ALTER TABLE " + quoteIdentifier(table)
                    + " ADD CONSTRAINT " + quoteIdentifier(cons.getName())
                    + " UNIQUE (" + cons.getColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ");\n";
            case CHECK -> "ALTER TABLE " + quoteIdentifier(table)
                    + " ADD CONSTRAINT " + quoteIdentifier(cons.getName())
                    + (cons.getCheckClause() != null ? " CHECK (" + cons.getCheckClause() + ")" : "")
                    + ";\n";
            case PRIMARY_KEY -> "ALTER TABLE " + quoteIdentifier(table)
                    + " ADD " + getPrimaryKeyDefinitionSql(cons.getColumns()) + ";\n";
            case INDEX -> indexStatement(
                    IndexModel.builder().indexName(cons.getName()).columnNames(cons.getColumns()).build(), table);
            default -> "";
        };
    }

    @Override
    public String getDropConstraintSql(String table, ConstraintModel cons) {
        return switch (cons.getType()) {
            // PG: UNIQUE constraints are named constraints, drop via DROP CONSTRAINT
            case UNIQUE -> "ALTER TABLE " + quoteIdentifier(table)
                    + " DROP CONSTRAINT " + quoteIdentifier(cons.getName()) + ";\n";
            case CHECK -> "ALTER TABLE " + quoteIdentifier(table)
                    + " DROP CONSTRAINT " + quoteIdentifier(cons.getName()) + ";\n";
            // ConstraintModel은 이름을 갖고 있으므로 우선 사용, 없으면 {table}_pkey로 fallback
            case PRIMARY_KEY -> {
                String pkName = (cons.getName() != null && !cons.getName().isBlank())
                        ? cons.getName()
                        : PostgreSqlUtil.defaultPkConstraintName(table);
                yield "ALTER TABLE " + quoteIdentifier(table)
                        + " DROP CONSTRAINT " + quoteIdentifier(pkName) + ";\n";
            }
            case INDEX -> getDropIndexSql(table, cons.getName());
            default -> "";
        };
    }

    @Override
    public String getModifyConstraintSql(String table, ConstraintModel newCons, ConstraintModel oldCons) {
        return getDropConstraintSql(table, oldCons) + getAddConstraintSql(table, newCons);
    }

    @Override
    public String indexStatement(IndexModel idx, String table) {
        String cols = idx.getColumnNames().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "CREATE INDEX " + quoteIdentifier(idx.getIndexName())
                + " ON " + quoteIdentifier(table) + " (" + cols + ");\n";
    }

    @Override
    public String getDropIndexSql(String table, IndexModel index) {
        return "DROP INDEX IF EXISTS " + quoteIdentifier(index.getIndexName()) + ";\n";
    }

    public String getDropIndexSql(String table, String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Index name must not be null/blank");
        }
        return "DROP INDEX IF EXISTS " + quoteIdentifier(indexName) + ";\n";
    }

    @Override
    public String getModifyIndexSql(String table, IndexModel newIndex, IndexModel oldIndex) {
        return getDropIndexSql(table, oldIndex) + indexStatement(newIndex, table);
    }

    // ── DdlDialect · Relationships ───────────────────────────────────────────

    @Override
    public String getAddRelationshipSql(String table, RelationshipModel rel) {
        if (rel.isNoConstraint()) return "";

        String targetTable = rel.getTableName() != null ? rel.getTableName() : table;
        String constraintName = rel.getConstraintName() != null ? rel.getConstraintName()
                : "fk_" + targetTable + "_" + String.join("_", rel.getColumns() != null ? rel.getColumns() : List.of());

        String fkColumns = rel.getColumns() != null
                ? rel.getColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(","))
                : "";
        String referencedColumns = rel.getReferencedColumns() != null
                ? rel.getReferencedColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(","))
                : "";

        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(quoteIdentifier(targetTable))
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
        if (rel.isNoConstraint()) return "";

        String targetTable = rel.getTableName() != null ? rel.getTableName() : table;
        String constraintName = rel.getConstraintName() != null ? rel.getConstraintName()
                : "fk_" + targetTable + "_" + String.join("_", rel.getColumns() != null ? rel.getColumns() : List.of());

        // PG uses DROP CONSTRAINT, not DROP FOREIGN KEY
        return "ALTER TABLE " + quoteIdentifier(targetTable)
                + " DROP CONSTRAINT " + quoteIdentifier(constraintName) + ";\n";
    }

    @Override
    public String getModifyRelationshipSql(String table, RelationshipModel newRel, RelationshipModel oldRel) {
        return getDropRelationshipSql(table, oldRel) + getAddRelationshipSql(table, newRel);
    }

    // ── IdentityDialect ──────────────────────────────────────────────────────

    @Override
    public String getIdentityClause(ColumnModel c) {
        // GENERATED BY DEFAULT allows explicit ID inserts (useful for test data & migrations)
        return " GENERATED BY DEFAULT AS IDENTITY";
    }

    // ── SequenceDialect ──────────────────────────────────────────────────────

    @Override
    public String getCreateSequenceSql(SequenceModel seq) {
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ")
                .append(qualifySequenceName(seq));
        // null = "not set → use DB default"; non-null = user-specified (including 0 and negatives)
        // Option order follows PG CREATE SEQUENCE docs: INCREMENT → MINVALUE → MAXVALUE → START WITH → CACHE
        if (seq.getAllocationSize() != null)
            sb.append("\n    INCREMENT BY ").append(seq.getAllocationSize());
        if (seq.getMinValue() != null)
            sb.append("\n    MINVALUE ").append(seq.getMinValue());
        if (seq.getMaxValue() != null)
            sb.append("\n    MAXVALUE ").append(seq.getMaxValue());
        if (seq.getInitialValue() != null)
            sb.append("\n    START WITH ").append(seq.getInitialValue());
        if (seq.getCache() != null)
            sb.append("\n    CACHE ").append(seq.getCache());
        sb.append(";\n");
        return sb.toString();
    }

    @Override
    public String getDropSequenceSql(SequenceModel seq) {
        return "DROP SEQUENCE IF EXISTS " + qualifySequenceName(seq) + ";\n";
    }

    @Override
    public String getAlterSequenceSql(SequenceModel newSeq, SequenceModel oldSeq) {
        StringBuilder sb = new StringBuilder("ALTER SEQUENCE ").append(qualifySequenceName(newSeq));
        boolean changed = false;

        if (!Objects.equals(newSeq.getAllocationSize(), oldSeq.getAllocationSize())
                && newSeq.getAllocationSize() != null) {
            sb.append(" INCREMENT BY ").append(newSeq.getAllocationSize());
            changed = true;
        }
        if (!Objects.equals(newSeq.getMinValue(), oldSeq.getMinValue())) {
            if (newSeq.getMinValue() != null) {
                sb.append(" MINVALUE ").append(newSeq.getMinValue());
            } else {
                sb.append(" NO MINVALUE");   // MINVALUE 제약 제거
            }
            changed = true;
        }
        if (!Objects.equals(newSeq.getMaxValue(), oldSeq.getMaxValue())) {
            if (newSeq.getMaxValue() != null) {
                sb.append(" MAXVALUE ").append(newSeq.getMaxValue());
            } else {
                sb.append(" NO MAXVALUE");   // MAXVALUE 제약 제거
            }
            changed = true;
        }
        // RESTART WITH comes before CACHE per PG ALTER SEQUENCE docs
        if (!Objects.equals(newSeq.getInitialValue(), oldSeq.getInitialValue())
                && newSeq.getInitialValue() != null) {
            sb.append(" RESTART WITH ").append(newSeq.getInitialValue());
            changed = true;
        }
        if (!Objects.equals(newSeq.getCache(), oldSeq.getCache())
                && newSeq.getCache() != null) {
            sb.append(" CACHE ").append(newSeq.getCache());
            changed = true;
        }

        if (!changed) return "";
        sb.append(";\n");
        return sb.toString();
    }

    // ── TableGeneratorDialect ────────────────────────────────────────────────

    @Override
    public String getCreateTableGeneratorSql(TableGeneratorModel tg) {
        var stringType = getJavaTypeMapper().map("java.lang.String");
        return "CREATE TABLE IF NOT EXISTS " + quoteIdentifier(tg.getTable())
                + " ("
                + quoteIdentifier(tg.getPkColumnName()) + " " + getTableGeneratorPkColumnType() + " NOT NULL PRIMARY KEY, "
                + quoteIdentifier(tg.getValueColumnName()) + " " + getTableGeneratorValueColumnType() + " NOT NULL"
                + ");\n"
                + "INSERT INTO " + quoteIdentifier(tg.getTable())
                + " (" + quoteIdentifier(tg.getPkColumnName())
                + ", " + quoteIdentifier(tg.getValueColumnName()) + ")"
                + " VALUES ("
                + valueTransformer.quote(tg.getPkColumnValue(), stringType)
                + ", " + tg.getInitialValue()
                + ") ON CONFLICT DO NOTHING;\n";
    }

    @Override
    public String getDropTableGeneratorSql(TableGeneratorModel tg) {
        return "DROP TABLE IF EXISTS " + quoteIdentifier(tg.getTable()) + ";\n";
    }

    /**
     * 테이블 제너레이터 변경 마이그레이션을 생성합니다.
     *
     * <ul>
     *   <li>테이블 이름 변경 → DROP old + CREATE new (구조 재생성)
     *   <li>pkColumnName / valueColumnName 변경 → RENAME COLUMN
     *   <li>pkColumnValue 변경 → UPDATE row key
     *   <li>initialValue 변경 → UPDATE row value (애플리케이션이 아직 카운터를 증가시키지 않았을 때만 안전)
     *   <li>allocationSize 변경 → SQL 불필요 (애플리케이션 레벨 배치 크기)
     * </ul>
     */
    @Override
    public String getAlterTableGeneratorSql(TableGeneratorModel newTg, TableGeneratorModel oldTg) {
        // 테이블 이름이 바뀌면 구조 전체가 달라지므로 재생성
        if (!Objects.equals(newTg.getTable(), oldTg.getTable())) {
            return getDropTableGeneratorSql(oldTg) + getCreateTableGeneratorSql(newTg);
        }

        StringBuilder sb = new StringBuilder();
        String table     = quoteIdentifier(newTg.getTable());
        var    stringType = getJavaTypeMapper().map("java.lang.String");

        // 컬럼 이름 변경은 데이터 UPDATE보다 먼저 실행 (이후 UPDATE는 new 컬럼명 사용)
        if (!Objects.equals(newTg.getPkColumnName(), oldTg.getPkColumnName())) {
            sb.append("ALTER TABLE ").append(table)
              .append(" RENAME COLUMN ").append(quoteIdentifier(oldTg.getPkColumnName()))
              .append(" TO ").append(quoteIdentifier(newTg.getPkColumnName())).append(";\n");
        }
        if (!Objects.equals(newTg.getValueColumnName(), oldTg.getValueColumnName())) {
            sb.append("ALTER TABLE ").append(table)
              .append(" RENAME COLUMN ").append(quoteIdentifier(oldTg.getValueColumnName()))
              .append(" TO ").append(quoteIdentifier(newTg.getValueColumnName())).append(";\n");
        }

        // 식별 키 값 변경 (RENAME COLUMN 이후이므로 new pkColumnName 사용)
        if (!Objects.equals(newTg.getPkColumnValue(), oldTg.getPkColumnValue())) {
            sb.append("UPDATE ").append(table)
              .append(" SET ").append(quoteIdentifier(newTg.getPkColumnName()))
              .append(" = ").append(valueTransformer.quote(newTg.getPkColumnValue(), stringType))
              .append(" WHERE ").append(quoteIdentifier(newTg.getPkColumnName()))
              .append(" = ").append(valueTransformer.quote(oldTg.getPkColumnValue(), stringType))
              .append(";\n");
        }

        // 초기값 변경 (new pkColumnValue와 new pkColumnName 기준으로 갱신)
        if (newTg.getInitialValue() != oldTg.getInitialValue()) {
            sb.append("UPDATE ").append(table)
              .append(" SET ").append(quoteIdentifier(newTg.getValueColumnName()))
              .append(" = ").append(newTg.getInitialValue())
              .append(" WHERE ").append(quoteIdentifier(newTg.getPkColumnName()))
              .append(" = ").append(valueTransformer.quote(newTg.getPkColumnValue(), stringType))
              .append(";\n");
        }

        return sb.toString();
    }

    // ── LiquibaseDialect ─────────────────────────────────────────────────────

    @Override
    public String getLiquibaseTypeName(ColumnModel column) {
        if (column.getSqlTypeOverride() != null && !column.getSqlTypeOverride().trim().isEmpty()) {
            return column.getSqlTypeOverride().trim();
        }

        String javaType = column.getConverterOutputType() != null
                ? column.getConverterOutputType()
                : column.getJavaType();
        int length    = column.getLength();
        int precision = column.getPrecision();
        int scale     = column.getScale();

        if (column.isLob()) {
            return javaType.equals("java.lang.String") ? "TEXT" : "BYTEA";
        }

        if (column.getEnumValues() != null && column.getEnumValues().length > 0) {
            return column.isEnumStringMapping() ? "VARCHAR(" + length + ")" : "INTEGER";
        }

        return switch (javaType) {
            case "java.lang.String"   -> "VARCHAR(" + length + ")";
            case "int", "java.lang.Integer" -> "INTEGER";
            case "long", "java.lang.Long"   -> "BIGINT";
            case "double", "java.lang.Double" -> "DOUBLE PRECISION";
            case "float",  "java.lang.Float"  -> "REAL";
            case "java.math.BigDecimal" -> "NUMERIC(" + precision + "," + scale + ")";
            case "java.math.BigInteger" -> "BIGINT";
            case "boolean", "java.lang.Boolean" -> "BOOLEAN";
            case "java.time.LocalDate"     -> "DATE";
            case "java.time.LocalDateTime" -> "TIMESTAMP";
            case "java.time.OffsetDateTime", "java.time.ZonedDateTime" -> "TIMESTAMP WITH TIME ZONE";
            case "java.util.Date" -> {
                if (column.getTemporalType() == TemporalType.DATE) yield "DATE";
                if (column.getTemporalType() == TemporalType.TIME) yield "TIME";
                yield "TIMESTAMP";
            }
            case "byte[]"       -> "BYTEA";
            case "java.util.UUID" -> "uuid";
            default             -> "VARCHAR(" + length + ")";
        };
    }

    @Override
    public boolean shouldUseAutoIncrement(GenerationStrategy strategy) {
        return strategy == GenerationStrategy.IDENTITY || strategy == GenerationStrategy.AUTO;
    }

    @Override
    public String getUuidDefaultValue() {
        // gen_random_uuid() is built-in since PG 13; available as pgcrypto extension in older versions
        return "gen_random_uuid()";
    }

    @Override
    public boolean pkDropNeedsName() { return true; }

    @Override
    public int getMaxIdentifierLength() { return 63; }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String qualifySequenceName(SequenceModel seq) {
        if (seq.getSchema() != null && !seq.getSchema().isBlank()) {
            return quoteIdentifier(seq.getSchema()) + "." + quoteIdentifier(seq.getName());
        }
        return quoteIdentifier(seq.getName());
    }

    private String resolveColumnSqlType(ColumnModel c) {
        if (c.getSqlTypeOverride() != null && !c.getSqlTypeOverride().trim().isEmpty()) {
            return c.getSqlTypeOverride().trim();
        }
        String typeKey = c.getConverterOutputType() != null ? c.getConverterOutputType() : c.getJavaType();
        JavaTypeMapper.JavaType javaTypeMapped = javaTypeMapper.map(typeKey);

        if (c.isLob())     return c.getJavaType().equals("java.lang.String") ? "TEXT" : "BYTEA";
        if (c.isVersion()) return c.getJavaType().equals("java.lang.Long") ? "BIGINT" : "TIMESTAMP";
        if (c.getTemporalType() != null) {
            return switch (c.getTemporalType()) {
                case DATE      -> "DATE";
                case TIME      -> "TIME";
                case TIMESTAMP -> "TIMESTAMP";
                default        -> javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
            };
        }
        if (c.getEnumValues() != null && c.getEnumValues().length > 0) {
            return c.isEnumStringMapping()
                    ? "VARCHAR(" + (c.getLength() > 0 ? c.getLength() : 255) + ")"
                    : "INTEGER";
        }
        return javaTypeMapped.getSqlType(c.getLength(), c.getPrecision(), c.getScale());
    }

    private String resolveDefaultExpression(ColumnModel c) {
        if (c.getDefaultValue() != null) {
            String typeKey = c.getConverterOutputType() != null ? c.getConverterOutputType() : c.getJavaType();
            return valueTransformer.quote(c.getDefaultValue(), javaTypeMapper.map(typeKey));
        }
        if (c.getGenerationStrategy() == GenerationStrategy.UUID && getUuidDefaultValue() != null) {
            return getUuidDefaultValue();
        }
        String typeKey = c.getConverterOutputType() != null ? c.getConverterOutputType() : c.getJavaType();
        JavaTypeMapper.JavaType mapped = javaTypeMapper.map(typeKey);
        if (mapped.getDefaultValue() != null && !c.isLob()) {
            return valueTransformer.quote(mapped.getDefaultValue(), mapped);
        }
        return null;
    }
}
