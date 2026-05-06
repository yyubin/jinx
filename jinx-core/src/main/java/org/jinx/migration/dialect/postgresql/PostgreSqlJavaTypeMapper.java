package org.jinx.migration.dialect.postgresql;

import lombok.Getter;
import org.jinx.migration.spi.JavaTypeMapper;

import java.util.Map;

import static java.util.Map.entry;

public class PostgreSqlJavaTypeMapper implements JavaTypeMapper {

    private record SqlType(String template, boolean usesLength, boolean usesPrecisionScale) {
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

    private static class PgJavaType implements JavaTypeMapper.JavaType {
        @Getter private final String javaClassName;
        private final SqlType sqlType;
        private final boolean needsQuotes;
        private final String defaultValue;

        PgJavaType(String javaClassName, SqlType sqlType, boolean needsQuotes, String defaultValue) {
            this.javaClassName = javaClassName;
            this.sqlType = sqlType;
            this.needsQuotes = needsQuotes;
            this.defaultValue = defaultValue;
        }

        @Override public String getSqlType(int length, int precision, int scale) { return sqlType.format(length, precision, scale); }
        @Override public boolean needsQuotes() { return needsQuotes; }
        @Override public String getDefaultValue() { return defaultValue; }
    }

    private static final PgJavaType UNKNOWN_TYPE = new PgJavaType(
            "unknown", new SqlType("TEXT", false, false), true, null);

    private static final Map<String, PgJavaType> TYPE_MAP = Map.ofEntries(
            // Boxed types
            entry("java.lang.Integer",    new PgJavaType("java.lang.Integer",    new SqlType("INTEGER",                  false, false), false, null)),
            entry("java.lang.Long",       new PgJavaType("java.lang.Long",       new SqlType("BIGINT",                   false, false), false, null)),
            entry("java.lang.String",     new PgJavaType("java.lang.String",     new SqlType("VARCHAR(%d)",              true,  false), true,  null)),
            entry("java.lang.Double",     new PgJavaType("java.lang.Double",     new SqlType("DOUBLE PRECISION",         false, false), false, null)),
            entry("java.lang.Float",      new PgJavaType("java.lang.Float",      new SqlType("REAL",                     false, false), false, null)),
            entry("java.math.BigDecimal", new PgJavaType("java.math.BigDecimal", new SqlType("NUMERIC(%d,%d)",           false, true),  false, null)),
            entry("java.math.BigInteger", new PgJavaType("java.math.BigInteger", new SqlType("BIGINT",                   false, false), false, null)),
            entry("java.lang.Boolean",    new PgJavaType("java.lang.Boolean",    new SqlType("BOOLEAN",                  false, false), false, "false")),
            // Date / time (java.time)
            entry("java.time.LocalDate",        new PgJavaType("java.time.LocalDate",        new SqlType("DATE",                    false, false), true, null)),
            entry("java.time.LocalDateTime",    new PgJavaType("java.time.LocalDateTime",    new SqlType("TIMESTAMP",               false, false), true, null)),
            entry("java.time.LocalTime",        new PgJavaType("java.time.LocalTime",        new SqlType("TIME",                    false, false), true, null)),
            entry("java.time.OffsetDateTime",   new PgJavaType("java.time.OffsetDateTime",   new SqlType("TIMESTAMP WITH TIME ZONE",false, false), true, null)),
            entry("java.time.ZonedDateTime",    new PgJavaType("java.time.ZonedDateTime",    new SqlType("TIMESTAMP WITH TIME ZONE",false, false), true, null)),
            entry("java.time.Instant",          new PgJavaType("java.time.Instant",          new SqlType("TIMESTAMP WITH TIME ZONE",false, false), true, null)),
            // Legacy date/time
            entry("java.util.Date",             new PgJavaType("java.util.Date",             new SqlType("TIMESTAMP",               false, false), true, null)),
            entry("java.sql.Date",              new PgJavaType("java.sql.Date",              new SqlType("DATE",                    false, false), true, null)),
            entry("java.sql.Time",              new PgJavaType("java.sql.Time",              new SqlType("TIME",                    false, false), true, null)),
            entry("java.sql.Timestamp",         new PgJavaType("java.sql.Timestamp",         new SqlType("TIMESTAMP",               false, false), true, null)),
            // Binary
            entry("byte[]",               new PgJavaType("byte[]",               new SqlType("BYTEA",                    false, false), false, null)),
            // UUID
            entry("java.util.UUID",       new PgJavaType("java.util.UUID",       new SqlType("uuid",                     false, false), false, null)),
            // Primitive types
            entry("int",     new PgJavaType("int",     new SqlType("INTEGER",          false, false), false, null)),
            entry("long",    new PgJavaType("long",    new SqlType("BIGINT",           false, false), false, null)),
            entry("double",  new PgJavaType("double",  new SqlType("DOUBLE PRECISION", false, false), false, null)),
            entry("float",   new PgJavaType("float",   new SqlType("REAL",             false, false), false, null)),
            entry("boolean", new PgJavaType("boolean", new SqlType("BOOLEAN",         false, false), false, "false")),
            entry("byte",    new PgJavaType("byte",    new SqlType("SMALLINT",         false, false), false, null)),
            entry("short",   new PgJavaType("short",   new SqlType("SMALLINT",         false, false), false, null)),
            entry("char",    new PgJavaType("char",    new SqlType("CHAR(1)",           false, false), true,  null))
    );

    @Override
    public JavaTypeMapper.JavaType map(String className) {
        return TYPE_MAP.getOrDefault(className, UNKNOWN_TYPE);
    }
}
