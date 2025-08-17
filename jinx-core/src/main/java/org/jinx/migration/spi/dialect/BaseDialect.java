package org.jinx.migration.spi.dialect;

import org.jinx.migration.JavaTypeMapper;
import org.jinx.migration.MigrationVisitor;
import org.jinx.migration.ValueTransformer;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.DiffResult;

public interface BaseDialect extends Dialect {
    SqlGeneratingVisitor createVisitor(DiffResult.ModifiedEntity diff);
    ValueTransformer getValueTransformer();
    String quoteIdentifier(String raw);
    JavaTypeMapper getJavaTypeMapper();
}
