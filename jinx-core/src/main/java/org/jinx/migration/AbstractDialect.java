package org.jinx.migration;

import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractDialect implements Dialect{
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

    public String getPrimaryKeyDefinitionSql(List<String> pk) {
        if (pk == null || pk.isEmpty()) {
            return "";
        }
        return "PRIMARY KEY (" + pk.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
    }
}