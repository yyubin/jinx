package org.jinx.migration.dialect.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.jinx.migration.DatabaseType;
import org.jinx.model.DialectBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MySqlVisitorProviderTest {

    private MySqlVisitorProvider provider = new MySqlVisitorProvider();

    @Test
    @DisplayName("DatabaseType이 MYSQL이라면 supports는 true를 반환해야 한다")
    void supportsMYSQL() {
        DialectBundle bundle = mock(DialectBundle.class);
        when(bundle.databaseType()).thenReturn(DatabaseType.MYSQL);

        boolean isSupport = provider.supports(bundle);

        assertTrue(isSupport);
    }

    @Test
    @DisplayName("DatabaseType이 null이라면 supports는 false를 반환해야 한다")
    void supportsNull() {
        DialectBundle bundle = mock(DialectBundle.class);
        when(bundle.databaseType()).thenReturn(null);

        boolean isSupport = provider.supports(bundle);

        assertFalse(isSupport);
    }

}