package org.jinx.migration;

import org.jinx.migration.dialect.mysql.MySqlMigrationVisitor;
import org.jinx.migration.dialect.mysql.MySqlTableGeneratorVisitor;
import org.jinx.migration.spi.dialect.*;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.VisitorProviders;
import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisitorFactoryTest {

    @Test
    @DisplayName("MYSQL 번들: tableVisitor / contentVisitor는 MySqlMigrationVisitor, sequence는 empty, tableGenerator는 Optional")
    void providers_mysql_with_and_without_table_generator() {
        // given
        DdlDialect ddl = mock(DdlDialect.class);

        // (1) tableGenerator 없는 번들
        DialectBundle noTg = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();
        VisitorProviders p1 = VisitorFactory.forBundle(noTg);

        // tableVisitor
        TableVisitor tv = p1.tableVisitor().get();
        assertNotNull(tv);
        assertTrue(tv instanceof MySqlMigrationVisitor);

        // tableContentVisitor
        DiffResult.ModifiedEntity me = mock(DiffResult.ModifiedEntity.class);
        EntityModel newEntity = mock(EntityModel.class);
        when(newEntity.getTableName()).thenReturn("orders");
        when(me.getNewEntity()).thenReturn(newEntity);

        EntityModel oldEntity = mock(EntityModel.class);
        when(oldEntity.getTableName()).thenReturn("orders_old");
        when(me.getOldEntity()).thenReturn(oldEntity);


        TableContentVisitor tcv = p1.tableContentVisitor().apply(me);
        assertNotNull(tcv);
        assertTrue(tcv instanceof MySqlMigrationVisitor);

        // sequence: MySQL 미지원
        assertTrue(p1.sequenceVisitor().isEmpty());

        // tableGenerator: absent
        assertTrue(p1.tableGeneratorVisitor().isEmpty());

        // (2) tableGenerator 있는 번들
        TableGeneratorDialect tgDialect = mock(TableGeneratorDialect.class);
        DialectBundle withTg = DialectBundle.builder(ddl, DatabaseType.MYSQL)
                .tableGenerator(tgDialect)
                .build();

        VisitorProviders p2 = VisitorFactory.forBundle(withTg);

        // tableGenerator: present & 생성 타입 확인
        Optional<Supplier<TableGeneratorVisitor>> tgOpt = p2.tableGeneratorVisitor();
        assertTrue(tgOpt.isPresent());
        TableGeneratorVisitor tgv = tgOpt.get().get();
        assertNotNull(tgv);
        assertTrue(tgv instanceof MySqlTableGeneratorVisitor);
    }
}
