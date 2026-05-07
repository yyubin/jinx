package org.jinx.migration.dialect.postgresql;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PostgreSqlUtil {

    private PostgreSqlUtil() {}

    // PostgreSQL 17/18 reserved keywords (SQL:2016 + PG extensions)
    private static final Set<String> PG_KEYWORDS = Stream.of(
            "ALL", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC",
            "ASYMMETRIC", "AUTHORIZATION", "BETWEEN", "BIGINT", "BINARY", "BIT",
            "BOOLEAN", "BOTH", "CASE", "CAST", "CHAR", "CHARACTER", "CHECK",
            "COALESCE", "COLLATE", "COLLATION", "COLUMN", "CONCURRENTLY", "CONSTRAINT",
            "CREATE", "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_ROLE",
            "CURRENT_SCHEMA", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "DEC", "DECIMAL", "DEFAULT", "DEFERRABLE", "DEFERRED", "DESC",
            "DISTINCT", "DO", "ELSE", "END", "EXCEPT", "EXISTS", "EXTRACT",
            "FALSE", "FETCH", "FILTER", "FIRST", "FLOAT", "FOR", "FOREIGN",
            "FREEZE", "FROM", "FULL", "GRANT", "GROUP", "GROUPING", "HAVING",
            "ILIKE", "IN", "INITIALLY", "INNER", "INOUT", "INT", "INTEGER",
            "INTERSECT", "INTERVAL", "INTO", "IS", "ISNULL", "JOIN", "LAST",
            "LATERAL", "LEADING", "LEFT", "LIKE", "LIMIT", "LOCALTIME",
            "LOCALTIMESTAMP", "NATIONAL", "NATURAL", "NCHAR", "NONE", "NOT",
            "NOTNULL", "NULL", "NULLIF", "NUMERIC", "OFFSET", "ON", "ONLY",
            "OR", "ORDER", "OUT", "OUTER", "OVER", "OVERLAPS", "PLACING",
            "POSITION", "PRECISION", "PRIMARY", "REAL", "REFERENCES", "RETURNING",
            "RIGHT", "ROW", "SELECT", "SESSION_USER", "SETOF", "SIMILAR", "SMALLINT",
            "SOME", "SUBSTRING", "SYMMETRIC", "TABLE", "TABLESAMPLE", "THEN",
            "TIME", "TIMESTAMP", "TO", "TRAILING", "TREAT", "TRIM", "TRUE",
            "UNION", "UNIQUE", "USER", "USING", "VALUES", "VARCHAR", "VARIADIC",
            "VERBOSE", "WHEN", "WHERE", "WINDOW", "WITH", "XMLEXISTS",
            // Additional PG non-reserved that still conflict in most contexts
            "INDEX", "SEQUENCE", "TYPE", "SCHEMA", "DATABASE", "TRIGGER",
            "FUNCTION", "PROCEDURE", "ROLE", "VIEW", "RULE", "OPERATOR",
            "LANGUAGE", "AGGREGATE", "DOMAIN", "RANGE", "SERVER", "FOREIGN",
            "WRAPPER", "POLICY", "PUBLICATION", "SUBSCRIPTION", "STATISTICS",
            "TRANSFORM", "CONVERSION", "TEXT", "NAME", "PATH", "DATA",
            "TEMP", "TEMPORARY", "UNLOGGED", "LOGGED", "GLOBAL", "LOCAL",
            "ABSOLUTE", "RELATIVE", "BACKWARD", "FORWARD", "SCROLL", "NO",
            "HOLD", "DECLARE", "OPEN", "CLOSE", "FETCH", "MOVE", "COPY",
            "LOCK", "NOTIFY", "LISTEN", "UNLISTEN", "LOAD", "VACUUM",
            "ANALYZE", "CLUSTER", "REINDEX", "RESET", "SET", "SHOW",
            "DISCARD", "CALL", "CHECKPOINT", "SECURITY", "DEFINER", "INVOKER",
            "COST", "ROWS", "CALLED", "RETURNS", "STRICT", "EXTERNAL", "SAFE",
            "VOLATILE", "IMMUTABLE", "STABLE", "LEAKPROOF", "WINDOW",
            "ABORT", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "PREPARE",
            "EXECUTE", "DEALLOCATE", "EXPLAIN", "BEGIN", "START", "END",
            "TRANSACTION", "ISOLATION", "LEVEL", "READ", "WRITE", "ONLY",
            "SERIALIZABLE", "REPEATABLE", "COMMITTED", "UNCOMMITTED"
    ).map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());

    public static boolean isKeyword(String name) {
        return name != null && PG_KEYWORDS.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Returns the default PK constraint name PG uses when no name is specified.
     * Convention: {tableName}_pkey
     */
    public static String defaultPkConstraintName(String tableName) {
        return tableName + "_pkey";
    }
}
