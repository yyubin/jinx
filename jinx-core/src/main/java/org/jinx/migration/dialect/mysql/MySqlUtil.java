package org.jinx.migration.dialect.mysql;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MySQL 관련 유틸리티, 특히 예약어 처리를 담당합니다.
 */
public final class MySqlUtil {

    private MySqlUtil() {}

    // MySQL 8.0 기준 예약어 목록
    private static final Set<String> MYSQL_KEYWORDS = Stream.of(
            "ACCESSIBLE", "ADD", "ALL", "ALTER", "ANALYZE", "AND", "AS", "ASC",
            "ASENSITIVE", "BEFORE", "BETWEEN", "BIGINT", "BINARY", "BLOB",
            "BOTH", "BY", "CALL", "CASCADE", "CASE", "CHANGE", "CHAR",
            "CHARACTER", "CHECK", "COLLATE", "COLUMN", "CONDITION", "CONSTRAINT",
            "CONTINUE", "CONVERT", "CREATE", "CROSS", "CUBE", "CUME_DIST",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "CURSOR", "DATABASE", "DATABASES", "DAY_HOUR", "DAY_MICROSECOND",
            "DAY_MINUTE", "DAY_SECOND", "DEC", "DECIMAL", "DECLARE", "DEFAULT",
            "DELAYED", "DELETE", "DENSE_RANK", "DESC", "DESCRIBE", "DETERMINISTIC",
            "DISTINCT", "DISTINCTROW", "DIV", "DOUBLE", "DROP", "DUAL", "EACH",
            "ELSE", "ELSEIF", "EMPTY", "ENCLOSED", "ESCAPED", "EXCEPT", "EXISTS",
            "EXIT", "EXPLAIN", "FALSE", "FETCH", "FIRST_VALUE", "FLOAT", "FLOAT4",
            "FLOAT8", "FOR", "FORCE", "FOREIGN", "FROM", "FULLTEXT", "FUNCTION",
            "GENERATED", "GET", "GRANT", "GROUP", "GROUPING", "GROUPS", "HAVING",
            "HIGH_PRIORITY", "HOUR_MICROSECOND", "HOUR_MINUTE", "HOUR_SECOND",
            "IF", "IGNORE", "IN", "INDEX", "INFILE", "INNER", "INOUT",
            "INSENSITIVE", "INSERT", "INT", "INT1", "INT2", "INT3", "INT4", "INT8",
            "INTEGER", "INTERVAL", "INTO", "IO_AFTER_GTIDS", "IO_BEFORE_GTIDS",
            "IS", "ITERATE", "JOIN", "JSON_TABLE", "KEY", "KEYS", "KILL",
            "LAG", "LAST_VALUE", "LEAD", "LEADING", "LEAVE", "LEFT", "LIKE",
            "LIMIT", "LINEAR", "LINES", "LOAD", "LOCALTIME", "LOCALTIMESTAMP",
            "LOCK", "LONG", "LONGBLOB", "LONGTEXT", "LOOP", "LOW_PRIORITY",
            "MASTER_BIND", "MASTER_SSL_VERIFY_SERVER_CERT", "MATCH", "MAXVALUE",
            "MEDIUMBLOB", "MEDIUMINT", "MEDIUMTEXT", "MIDDLEINT",
            "MINUTE_MICROSECOND", "MINUTE_SECOND", "MOD", "MODIFIES",
            "NATURAL", "NOT", "NO_WRITE_TO_BINLOG", "NTH_VALUE", "NTILE",
            "NULL", "NUMERIC", "OF", "ON", "OPTIMIZE", "OPTIMIZER_COSTS", "OPTION",
            "OPTIONALLY", "OR", "ORDER", "OUT", "OUTER", "OUTFILE", "OVER",
            "PARTITION", "PERCENT_RANK", "PRECISION", "PRIMARY", "PROCEDURE",
            "PURGE", "RANGE", "RANK", "READ", "READS", "READ_WRITE", "REAL",
            "RECURSIVE", "REFERENCES", "REGEXP", "RELEASE", "RENAME", "REPEAT",
            "REPLACE", "REQUIRE", "RESIGNAL", "RESTRICT", "RETURN", "REVOKE",
            "RIGHT", "RLIKE", "ROW", "ROWS", "ROW_NUMBER", "SCHEMA", "SCHEMAS",
            "SECOND_MICROSECOND", "SELECT", "SENSITIVE", "SEPARATOR", "SET",
            "SHOW", "SIGNAL", "SMALLINT", "SPATIAL", "SPECIFIC", "SQL",
            "SQLEXCEPTION", "SQLSTATE", "SQLWARNING", "SQL_BIG_RESULT",
            "SQL_CALC_FOUND_ROWS", "SQL_SMALL_RESULT", "SSL", "STARTING",
            "STORED", "STRAIGHT_JOIN", "SYSTEM", "TABLE", "TERMINATED", "THEN",
            "TINYBLOB", "TINYINT", "TINYTEXT", "TO", "TRAILING", "TRIGGER",
            "TRUE", "UNDO", "UNION", "UNIQUE", "UNLOCK", "UNSIGNED", "UPDATE",
            "USAGE", "USE", "USING", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP",
            "VALUES", "VARBINARY", "VARCHAR", "VARCHARACTER", "VARYING", "WHEN",
            "WHERE", "WHILE", "WINDOW", "WITH", "WRITE", "XOR", "YEAR_MONTH",
            "ZEROFILL"
    ).map(String::toUpperCase).collect(Collectors.toSet());

    /**
     * 주어진 이름이 MySQL 예약어인지 확인하고, 예약어일 경우 끝에 '_'를 붙여 반환합니다.
     * @param name 확인할 식별자 이름
     * @return 예약어가 아닐 경우 원래 이름, 예약어일 경우 수정된 이름
     */
    public static String escapeKeyword(String name) {
        if (name != null && MYSQL_KEYWORDS.contains(name.toUpperCase())) {
            return name + "_";
        }
        return name;
    }

    public static boolean isKeyword(String name) {
        return MYSQL_KEYWORDS.contains(name.toUpperCase());
    }

    // Mysql pk 정렬 전용 유틸 메서드
    public static List<String> reorderForIdentity(List<String> pk,
                                                   List<ColumnModel> cols) {
        String identity = cols.stream()
                .filter(c -> c.getGenerationStrategy() == GenerationStrategy.IDENTITY)
                .map(ColumnModel::getColumnName)
                .filter(pk::contains)
                .findFirst()
                .orElse(null);

        if (identity == null) return pk;
        List<String> reordered = new ArrayList<>(pk);
        reordered.remove(identity);
        reordered.add(0, identity);
        return reordered;
    }

}