package org.jinx.migration.dialect.mysql;

import lombok.Getter;
import org.jinx.migration.spi.JavaTypeMapper;

import java.util.Map;

import static java.util.Map.entry;

public class MySqlJavaTypeMapper implements JavaTypeMapper {

    private static record SqlType(String template, boolean usesLength, boolean usesPrecisionScale) {
        String format(int length, int precision, int scale) {
            if (usesPrecisionScale) {
                return String.format(template, precision > 0 ? precision : 10, scale > 0 ? scale : 2);
            }
            if (usesLength) {
                return String.format(template, length > 0 ? length : 255);
            }
            return template;
        }
    }

    private static class MysqlJavaType implements JavaTypeMapper.JavaType {
        @Getter
        private final String javaClassName;
        private final SqlType sqlType;
        private final boolean needsQuotes;
        private final String defaultValue;

        MysqlJavaType(String javaClassName, SqlType sqlType, boolean needsQuotes, String defaultValue) {
            this.javaClassName = javaClassName;
            this.sqlType = sqlType;
            this.needsQuotes = needsQuotes;
            this.defaultValue = defaultValue;
        }

        @Override
        public String getSqlType(int length, int precision, int scale) {
            return sqlType.format(length, precision, scale);
        }

        @Override
        public boolean needsQuotes() {
            return needsQuotes;
        }

        @Override
        public String getDefaultValue() {
            return defaultValue;
        }
    }

    private static final MysqlJavaType UNKNOWN_TYPE = new MysqlJavaType(
            "unknown",
            new SqlType("TEXT", false, false),
            true,
            null
    );

    private static final Map<String, MysqlJavaType> TYPE_MAP = Map.ofEntries(
            // Boxed types
            entry("java.lang.Integer", new MysqlJavaType("java.lang.Integer", new SqlType("INT", false, false), false, null)),
            entry("java.lang.Long", new MysqlJavaType("java.lang.Long", new SqlType("BIGINT", false, false), false, null)),
            entry("java.lang.String", new MysqlJavaType("java.lang.String", new SqlType("VARCHAR(%d)", true, false), true, null)),
            entry("java.lang.Double", new MysqlJavaType("java.lang.Double", new SqlType("DOUBLE", false, false), false, null)),
            entry("java.lang.Float", new MysqlJavaType("java.lang.Float", new SqlType("FLOAT", false, false), false, null)),
            entry("java.math.BigDecimal", new MysqlJavaType("java.math.BigDecimal", new SqlType("DECIMAL(%d,%d)", false, true), false, null)),
            entry("java.lang.Boolean", new MysqlJavaType("java.lang.Boolean", new SqlType("TINYINT(1)", false, false), false, "0")),
            entry("java.time.LocalDate", new MysqlJavaType("java.time.LocalDate", new SqlType("DATE", false, false), true, null)),
            entry("java.time.LocalDateTime", new MysqlJavaType("java.time.LocalDateTime", new SqlType("TIMESTAMP(6)", false, false), true, null)),
            entry("java.math.BigInteger", new MysqlJavaType("java.math.BigInteger", new SqlType("BIGINT", false, false), false, null)),
            // Primitive types
            entry("int", new MysqlJavaType("int", new SqlType("INT", false, false), false, null)),
            entry("long", new MysqlJavaType("long", new SqlType("BIGINT", false, false), false, null)),
            entry("double", new MysqlJavaType("double", new SqlType("DOUBLE", false, false), false, null)),
            entry("float", new MysqlJavaType("float", new SqlType("FLOAT", false, false), false, null)),
            entry("boolean", new MysqlJavaType("boolean", new SqlType("TINYINT(1)", false, false), false, "0")),
            entry("byte", new MysqlJavaType("byte", new SqlType("TINYINT", false, false), false, null)),
            entry("short", new MysqlJavaType("short", new SqlType("SMALLINT", false, false), false, null)),
            entry("char", new MysqlJavaType("char", new SqlType("CHAR(1)", false, false), true, null))
    );

    @Override
    public JavaTypeMapper.JavaType map(String className) {
        return TYPE_MAP.getOrDefault(className, UNKNOWN_TYPE);
    }
}