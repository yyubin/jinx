package org.jinx.migration.dialect.mysql;

import org.jinx.migration.spi.JavaTypeMapper;
import org.jinx.migration.spi.ValueTransformer;

public class MySqlValueTransformer implements ValueTransformer {
    @Override
    public String quote(String value, JavaTypeMapper.JavaType type) {
        if (value == null) return "NULL";
        return "'" + value.replace("'", "''") + "'";
    }
}
