package org.jinx.model;

import org.jinx.migration.DatabaseType;
import org.jinx.migration.spi.dialect.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DialectBundleTest {

    @Test
    @DisplayName("기본 빌드: base==ddl, supports*는 false, require*는 예외")
    void basic_build_flags_and_require_throws() {
        DdlDialect ddl = mock(DdlDialect.class);

        DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

        // base==ddl
        assertSame(ddl, bundle.base());
        assertSame(ddl, bundle.ddl());

        // supports*
        assertFalse(bundle.supportsSequence());
        assertFalse(bundle.supportsTableGenerator());
        assertFalse(bundle.supportsLiquibase());
        assertFalse(bundle.supportsIdentity());

        // require* throws
        assertThrows(IllegalStateException.class, bundle::requireSequence);
        assertThrows(IllegalStateException.class, bundle::requireTableGenerator);
        assertThrows(IllegalStateException.class, bundle::requireLiquibase);

        // with* (ifPresent) 콜백은 호출되지 않음
        AtomicBoolean called = new AtomicBoolean(false);
        bundle.withSequence(s -> called.set(true));
        assertFalse(called.get());
        bundle.withTableGenerator(s -> called.set(true));
        assertFalse(called.get());
        bundle.withLiquibase(s -> called.set(true));
        assertFalse(called.get());
        bundle.withIdentity(s -> called.set(true));
        assertFalse(called.get());
    }

    @Test
    @DisplayName("옵션 다이얼렉트 제공 시 supports* true, require*는 주입 객체 반환, with* 콜백 호출")
    void options_present() {
        DdlDialect ddl = mock(DdlDialect.class);
        IdentityDialect id = mock(IdentityDialect.class);
        SequenceDialect seq = mock(SequenceDialect.class);
        TableGeneratorDialect tg = mock(TableGeneratorDialect.class);
        LiquibaseDialect lb = mock(LiquibaseDialect.class);

        DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL)
                .identity(id)
                .sequence(seq)
                .tableGenerator(tg)
                .liquibase(lb)
                .build();

        assertTrue(bundle.supportsIdentity());
        assertTrue(bundle.supportsSequence());
        assertTrue(bundle.supportsTableGenerator());
        assertTrue(bundle.supportsLiquibase());

        assertSame(seq, bundle.requireSequence());
        assertSame(tg, bundle.requireTableGenerator());
        assertSame(lb, bundle.requireLiquibase());

        // with* 콜백 호출 확인
        AtomicBoolean idCalled = new AtomicBoolean(false);
        AtomicBoolean seqCalled = new AtomicBoolean(false);
        AtomicBoolean tgCalled = new AtomicBoolean(false);
        AtomicBoolean lbCalled = new AtomicBoolean(false);

        bundle.withIdentity(x -> idCalled.set(true));
        bundle.withSequence(x -> seqCalled.set(true));
        bundle.withTableGenerator(x -> tgCalled.set(true));
        bundle.withLiquibase(x -> lbCalled.set(true));

        assertTrue(idCalled.get());
        assertTrue(seqCalled.get());
        assertTrue(tgCalled.get());
        assertTrue(lbCalled.get());
    }

    @Test
    @DisplayName("databaseType()은 전달된 enum을 그대로 반환한다")
    void database_type_exposed() {
        DdlDialect ddl = mock(DdlDialect.class);
        DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();
        assertEquals(DatabaseType.MYSQL, bundle.databaseType());
    }
}
