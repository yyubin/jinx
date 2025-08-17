package org.jinx.migration.spi;

public interface ValueTransformer {
    String quote(String value, JavaTypeMapper.JavaType type);
}