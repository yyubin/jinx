package org.jinx.migration;

import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;
import org.jinx.migration.spi.dialect.BaseDialect;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import java.util.Collection;

public abstract class AbstractDialect implements BaseDialect, DdlDialect {
    protected JavaTypeMapper javaTypeMapper;
    protected ValueTransformer valueTransformer;

    protected AbstractDialect() {
        this.javaTypeMapper = initializeJavaTypeMapper();
        this.valueTransformer = initializeValueTransformer();
    }

    protected abstract JavaTypeMapper initializeJavaTypeMapper();
    protected abstract ValueTransformer initializeValueTransformer();
    public abstract String quoteIdentifier(String identifier);

    @Override
    public ValueTransformer getValueTransformer() {
        return this.valueTransformer;
    }

    protected boolean columnIsIdentity(String colName, Collection<ColumnModel> allColumns) {
        return allColumns.stream()
                .filter(c -> c.getColumnName().equals(colName))
                .anyMatch(c -> c.getGenerationStrategy() == GenerationStrategy.IDENTITY);
    }

    public abstract String getDropPrimaryKeySql(String table, Collection<ColumnModel> currentColumns);
}