package org.jinx.migration;

import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractDialect implements Dialect {
    protected JavaTypeMapper javaTypeMapper;
    protected ValueTransformer valueTransformer;

    protected AbstractDialect() {
        this.javaTypeMapper = initializeJavaTypeMapper();
        this.valueTransformer = initializeValueTransformer();
    }

    protected abstract JavaTypeMapper initializeJavaTypeMapper();
    protected abstract ValueTransformer initializeValueTransformer();
    public abstract String quoteIdentifier(String identifier);
    protected abstract String getIdentityClause(ColumnModel c);

    @Override
    public ValueTransformer getValueTransformer() {
        return this.valueTransformer;
    }

    @Override
    public String getColumnDefinitionSql(ColumnModel c) {
        JavaTypeMapper.JavaType javaType = javaTypeMapper.map(c.getJavaType());
        ValueTransformer vt = valueTransformer;

        StringBuilder sb = new StringBuilder();
        sb.append(quoteIdentifier(c.getColumnName())).append(" ")
                .append(javaType.getSqlType(c.getLength(), c.getPrecision(), c.getScale()));

        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (c.getGenerationStrategy() == GenerationStrategy.IDENTITY) {
            sb.append(getIdentityClause(c));
        } else if (c.isManualPrimaryKey()) {
            sb.append(" PRIMARY KEY");
        }

        if (c.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(vt.quote(c.getDefaultValue(), javaType));
        } else if (javaType.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(vt.quote(javaType.getDefaultValue(), javaType));
        }

        return sb.toString();
    }

    protected boolean columnIsIdentity(String colName, Collection<ColumnModel> allColumns) {
        return allColumns.stream()
                .filter(c -> c.getColumnName().equals(colName))
                .anyMatch(c -> c.getGenerationStrategy() == GenerationStrategy.IDENTITY);
    }

    public abstract String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns);
}