package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;

public class PostgreSqlValueTransformer implements ValueTransformer {
    @Override
    public String quote(String value, JavaTypeMapper.JavaType type) {
        if (value == null) return "NULL";
        // PG (standard_conforming_strings = on, default since 9.1):
        // only single-quote needs doubling; backslash is literal
        return "'" + value.replace("'", "''") + "'";
    }
}
