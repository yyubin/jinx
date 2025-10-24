package org.jinx.migration.output;

import org.jinx.migration.MigrationGenerator;
import org.jinx.migration.VisitorFactory;
import org.jinx.migration.differs.SchemaDiffer;
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
import org.mockito.MockedConstruction;
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

class SqlRollbackHandlerTest {

    // 테이블 방문 시점과 무관하게 고정 SQL을 반환하는 스텁
    static class FixedTableVisitor implements TableVisitor {
        private final String sql;
        FixedTableVisitor(String sql) { this.sql = sql; }
        @Override public void visitAddedTable(org.jinx.model.EntityModel table) {}
        @Override public void visitDroppedTable(org.jinx.model.EntityModel table) {}
        @Override public void visitRenamedTable(org.jinx.model.DiffResult.RenamedTable renamed) {}
        @Override public String getGeneratedSql() { return sql; }
    }

    @Test
    @DisplayName("rollback-{version}.sql 로 쓰고, 내용은 롤백 헤더 + 방문자 SQL이 담긴다")
    void writesRollbackFile(@TempDir Path tempDir) throws IOException {
        // given
        SchemaModel oldSchema = SchemaModel.builder().version("1.0.0").build();
        SchemaModel newSchema = SchemaModel.builder().version("2.0.0").build();
        DialectBundle dialect = mock(DialectBundle.class);

        // handle에 들어오는 diff는 실제로 사용되지 않지만 더미로 준비
        DiffResult forwardDiff = mock(DiffResult.class);

        // rollbackDiff (SchemaDiffer.diff(next, old)의 반환값)
        DiffResult rollbackDiff = mock(DiffResult.class);
        when(rollbackDiff.getWarnings()).thenReturn(List.of());    // 경고 없음
        when(rollbackDiff.getModifiedTables()).thenReturn(List.of());
        doNothing().when(rollbackDiff).tableAccept(any(), any());
        doNothing().when(rollbackDiff).tableContentAccept(any(), any());
        doNothing().when(rollbackDiff).sequenceAccept(any(), any(), any());
        doNothing().when(rollbackDiff).tableGeneratorAccept(any(), any(), any());

        // VisitorProviders: tableVisitor가 고정 SQL("RB") 반환
        Supplier<TableVisitor> tv = () -> new FixedTableVisitor("RB");
        Function<DiffResult.ModifiedEntity, TableContentVisitor> tcv = me -> null; // 사용 안 함
        Function<EntityModel, TableContentVisitor> tcev = m -> null;
        VisitorProviders providers = new VisitorProviders(tv, tcv, tcev, Optional.empty(), Optional.empty());

        SqlRollbackHandler handler = new SqlRollbackHandler();

        // when + then (모킹 범위 설정)
        try (MockedConstruction<SchemaDiffer> mc =
                     mockConstruction(SchemaDiffer.class, (mock, context) -> {
                         when(mock.diff(eq(newSchema), eq(oldSchema))).thenReturn(rollbackDiff);
                     });
             MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {

            // MigrationGenerator가 사용할 팩토리 고정
            vf.when(() -> VisitorFactory.forBundle(dialect)).thenReturn(providers);

            handler.handle(forwardDiff, oldSchema, newSchema, dialect, tempDir);

            // 파일 검증
            Path out = tempDir.resolve("rollback-2.0.0.sql");
            assertTrue(Files.exists(out), "rollback 파일이 생성되어야 합니다.");

            String expected = String.join("\n",
                    "-- WARNING: this is rollback SQL for a migration",
                    "RB",
                    "RB"
            );
            String actual = Files.readString(out);
            assertEquals(expected, actual);

            // SchemaDiffer가 역순 인자(next, old)로 호출되었는지 확인
            assertEquals(1, mc.constructed().size());
            SchemaDiffer constructed = mc.constructed().get(0);
            verify(constructed, times(1)).diff(newSchema, oldSchema);
        }
    }
}
