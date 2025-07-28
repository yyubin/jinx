package org.jinx.migration;

public interface ValueTransformer {
    String quote(String value, JavaTypeMapper.JavaType type);
}