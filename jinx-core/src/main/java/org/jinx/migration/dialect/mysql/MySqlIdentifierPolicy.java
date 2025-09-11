package org.jinx.migration.dialect.mysql;

import org.jinx.migration.spi.IdentifierPolicy;

class MySqlIdentifierPolicy implements IdentifierPolicy {
    public int maxLength()          { return 64; }
    public String quote(String raw) { return "`" + raw + "`"; }
    public String normalizeCase(String raw) { return raw; }
    public boolean isKeyword(String raw) { return MySqlUtil.isKeyword(raw.toUpperCase()); }
}
