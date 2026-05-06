package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.IdentifierPolicy;

class PostgreSqlIdentifierPolicy implements IdentifierPolicy {
    @Override public int maxLength()              { return 63; }
    @Override public String quote(String raw)     { return "\"" + raw.replace("\"", "\"\"") + "\""; }
    @Override public String normalizeCase(String raw) { return raw.toLowerCase(); }
    @Override public boolean isKeyword(String raw){ return PostgreSqlUtil.isKeyword(raw.toUpperCase()); }
}
