package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.DatabaseType;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.dialect.SequenceDialect;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.DialectBundle;
import org.jinx.model.VisitorProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSqlVisitorProviderTest {

    private final PostgreSqlVisitorProvider provider = new PostgreSqlVisitorProvider();

    // ── supports ────────────────────────────────────────────────────────────

    @Test @DisplayName("POSTGRESQL이면 supports = true")
    void supports_postgresql_true() {
        DialectBundle bundle = mock(DialectBundle.class);
        when(bundle.databaseType()).thenReturn(DatabaseType.POSTGRESQL);
        assertTrue(provider.supports(bundle));
    }

    @Test @DisplayName("MYSQL이면 supports = false")
    void supports_mysql_false() {
        DialectBundle bundle = mock(DialectBundle.class);
        when(bundle.databaseType()).thenReturn(DatabaseType.MYSQL);
        assertFalse(provider.supports(bundle));
    }

    @Test @DisplayName("null이면 supports = false")
    void supports_null_false() {
        DialectBundle bundle = mock(DialectBundle.class);
        when(bundle.databaseType()).thenReturn(null);
        assertFalse(provider.supports(bundle));
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test @DisplayName("create → tableVisitor, tableContentVisitor, entityTableContentVisitor 모두 존재")
    void create_visitorsSuppliersPresent() {
        DialectBundle bundle = bundle(false, false);
        VisitorProviders vp = provider.create(bundle);

        assertNotNull(vp.tableVisitor(), "tableVisitor supplier 존재");
        assertNotNull(vp.tableContentVisitor(), "tableContentVisitor function 존재");
        assertNotNull(vp.entityTableContentVisitor(), "entityTableContentVisitor function 존재");
    }

    @Test @DisplayName("시퀀스 dialect 없으면 sequenceVisitor = empty")
    void create_noSequenceDialect_emptySequenceVisitor() {
        DialectBundle bundle = bundle(false, false);
        VisitorProviders vp = provider.create(bundle);
        assertTrue(vp.sequenceVisitor().isEmpty(), "시퀀스 미지원 시 empty");
    }

    @Test @DisplayName("시퀀스 dialect 있으면 sequenceVisitor 존재")
    void create_withSequenceDialect_sequenceVisitorPresent() {
        DialectBundle bundle = bundle(true, false);
        VisitorProviders vp = provider.create(bundle);
        assertTrue(vp.sequenceVisitor().isPresent(), "시퀀스 지원 시 visitor 존재");

        // supplier 호출하면 PostgreSqlSequenceVisitor 반환
        var seqVisitor = vp.sequenceVisitor().get().get();
        assertInstanceOf(PostgreSqlSequenceVisitor.class, seqVisitor);
    }

    @Test @DisplayName("TableGenerator dialect 없으면 tableGeneratorVisitor = empty")
    void create_noTableGenerator_emptyTgVisitor() {
        DialectBundle bundle = bundle(false, false);
        VisitorProviders vp = provider.create(bundle);
        assertTrue(vp.tableGeneratorVisitor().isEmpty());
    }

    @Test @DisplayName("TableGenerator dialect 있으면 tableGeneratorVisitor 존재")
    void create_withTableGenerator_tgVisitorPresent() {
        DialectBundle bundle = bundle(false, true);
        VisitorProviders vp = provider.create(bundle);
        assertTrue(vp.tableGeneratorVisitor().isPresent());
        assertInstanceOf(PostgreSqlTableGeneratorVisitor.class,
                vp.tableGeneratorVisitor().get().get());
    }

    @Test @DisplayName("tableVisitor supplier 호출 → PostgreSqlMigrationVisitor 반환")
    void create_tableVisitor_returnsMigrationVisitor() {
        DialectBundle bundle = bundle(false, false);
        VisitorProviders vp = provider.create(bundle);
        assertInstanceOf(PostgreSqlMigrationVisitor.class, vp.tableVisitor().get());
    }

    @Test @DisplayName("tableContentVisitor function 호출 → PostgreSqlMigrationVisitor 반환")
    void create_tableContentVisitor_returnsMigrationVisitor() {
        DialectBundle bundle = bundle(false, false);
        VisitorProviders vp = provider.create(bundle);

        org.jinx.model.EntityModel entity = org.jinx.model.EntityModel.builder()
                .tableName("t").build();
        org.jinx.model.DiffResult.ModifiedEntity diff =
                org.jinx.model.DiffResult.ModifiedEntity.builder().newEntity(entity).build();

        assertInstanceOf(PostgreSqlMigrationVisitor.class, vp.tableContentVisitor().apply(diff));
        assertInstanceOf(PostgreSqlMigrationVisitor.class, vp.entityTableContentVisitor().apply(entity));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private DialectBundle bundle(boolean withSequence, boolean withTableGenerator) {
        DdlDialect ddl = mock(DdlDialect.class);
        DialectBundle bundle = mock(DialectBundle.class);
        when(bundle.databaseType()).thenReturn(DatabaseType.POSTGRESQL);
        when(bundle.ddl()).thenReturn(ddl);

        if (withSequence) {
            SequenceDialect seqDialect = mock(SequenceDialect.class);
            when(bundle.sequence()).thenReturn(Optional.of(seqDialect));
        } else {
            when(bundle.sequence()).thenReturn(Optional.empty());
        }

        if (withTableGenerator) {
            TableGeneratorDialect tgDialect = mock(TableGeneratorDialect.class);
            when(bundle.tableGenerator()).thenReturn(Optional.of(tgDialect));
        } else {
            when(bundle.tableGenerator()).thenReturn(Optional.empty());
        }

        return bundle;
    }
}
