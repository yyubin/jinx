package org.jinx.migration.output;

import org.jinx.migration.VisitorFactory;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.VisitorProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlMigrationHandlerTest {

    // 간단한 고정 SQL을 내는 TableVisitor 스텁
    static class FixedSqlTableVisitor implements TableVisitor {
        private final String sql;
        FixedSqlTableVisitor(String sql) { this.sql = sql; }
        @Override public void visitAddedTable(org.jinx.model.EntityModel table) {}
        @Override public void visitDroppedTable(org.jinx.model.EntityModel table) {}
        @Override public void visitRenamedTable(org.jinx.model.DiffResult.RenamedTable renamed) {}
        @Override public String getGeneratedSql() { return sql; }
    }

    @Test
    @DisplayName("파일명은 migration-{version}.sql 이고, 내용은 생성된 SQL과 동일하다")
    void writesGeneratedSqlToExpectedFile(@TempDir Path tempDir) throws IOException {
        // given: 스키마/번들/디프
        SchemaModel oldSchema = SchemaModel.builder().version("1.0.0").build();
        SchemaModel newSchema = SchemaModel.builder().version("1.2.3").build();
        DialectBundle dialect = mock(DialectBundle.class);

        DiffResult diff = mock(DiffResult.class);
        when(diff.getWarnings()).thenReturn(List.of("--W1"));  // 경고 1개
        when(diff.getModifiedTables()).thenReturn(List.of());  // 수정 테이블 없음

        // accept 는 no-op
        doNothing().when(diff).tableAccept(any(), any());
        doNothing().when(diff).tableContentAccept(any(), any());
        doNothing().when(diff).sequenceAccept(any(), any(), any());
        doNothing().when(diff).tableGeneratorAccept(any(), any(), any());

        // VisitorProviders: tableVisitor만 사용 (두 번 호출되므로 동일 SQL 두 줄 예상)
        Supplier<TableVisitor> tvSupplier = () -> new FixedSqlTableVisitor("TBL");
        Function<DiffResult.ModifiedEntity, TableContentVisitor> tcvFactory = me -> null; // 사용 안 함
        Function<EntityModel, TableContentVisitor>  tcevFactory = m -> null;
        VisitorProviders providers = new VisitorProviders(
                tvSupplier, tcvFactory, tcevFactory,Optional.empty(), Optional.empty()
        );

        // VisitorFactory.forBundle(...) 을 providers 반환하도록 static mocking
        try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
            vf.when(() -> VisitorFactory.forBundle(dialect)).thenReturn(providers);

            SqlMigrationHandler handler = new SqlMigrationHandler();

            // when
            handler.handle(diff, oldSchema, newSchema, dialect, tempDir);

            // then: 파일명/내용 검증
            Path out = tempDir.resolve("migration-1.2.3.sql");
            assertTrue(Files.exists(out), "결과 파일이 존재해야 합니다.");

            String expected = String.join("\n",
                    "-- WARNING: --W1",
                    "TBL",
                    "TBL"
            );
            String actual = Files.readString(out);
            assertEquals(expected, actual);
        }
    }
}
