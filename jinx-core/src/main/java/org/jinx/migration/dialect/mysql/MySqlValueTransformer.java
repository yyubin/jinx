package org.jinx.migration.dialect.mysql;

import org.jinx.migration.JavaTypeMapper;
import org.jinx.migration.ValueTransformer;

public class MySqlValueTransformer implements ValueTransformer {
    @Override
    public String quote(String value, JavaTypeMapper.JavaType type) {
        if (value == null) return "NULL";
        return "'" + value.replace("'", "''") + "'";
    }
}
