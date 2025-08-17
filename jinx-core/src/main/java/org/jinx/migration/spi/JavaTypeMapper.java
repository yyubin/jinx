package org.jinx.migration.spi;

public interface JavaTypeMapper {
    JavaType map(String className);

    interface JavaType {
        String getSqlType(int length, int precision, int scale);
        boolean needsQuotes();
        String getDefaultValue();
    }
}
