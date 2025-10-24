package org.jinx.migration;

import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;
import org.jinx.model.*;
import org.jinx.testing.visitor.RecordingEntityTableContentVisitor;
import org.jinx.testing.visitor.RecordingTableContentVisitor;
import org.jinx.testing.visitor.RecordingTableVisitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationGeneratorTest {

    @Test
    @DisplayName("reverse + warnings + (DROP/RENAME) + (ADDED) + (ALTER) + (FK_ADD) 순서대로 SQL이 조립된다")
    void generateSql_full_flow() {
        // --- given: 번들/다이얼렉트 ---
        DdlDialect ddl = mock(DdlDialect.class);
        DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

        // --- given: DiffResult 목킹 및 스텁 ---
        DiffResult diff = mock(DiffResult.class);
        when(diff.getWarnings()).thenReturn(List.of("W1", "W2"));

        DiffResult.ModifiedEntity me = mock(DiffResult.ModifiedEntity.class);
        EntityModel newEntity = mock(EntityModel.class);
        when(newEntity.getTableName()).thenReturn("t_new");
        when(me.getNewEntity()).thenReturn(newEntity);
        when(diff.getModifiedTables()).thenReturn(List.of(me));

        // tableContentAccept: Phase 별로 녹화형 비지터에 visit* 호출 → SQL 축적
        doAnswer(inv -> {
            TableContentVisitor v = inv.getArgument(0);
            DiffResult.TableContentPhase phase = inv.getArgument(1);
            if (phase == DiffResult.TableContentPhase.DROP) {
                // DROP 단계: 컬럼/PK 제거
                var col = mock(ColumnModel.class);
                when(col.getColumnName()).thenReturn("age");
                v.visitDroppedColumn(col);
                v.visitDroppedPrimaryKey();
            }
            return null;
        }).when(diff).tableContentAccept(any(), eq(DiffResult.TableContentPhase.DROP));

        doAnswer(inv -> {
            TableContentVisitor v = inv.getArgument(0);
            DiffResult.TableContentPhase phase = inv.getArgument(1);
            if (phase == DiffResult.TableContentPhase.ALTER) {
                var newCol = mock(ColumnModel.class);
                when(newCol.getColumnName()).thenReturn("name");
                var oldCol = mock(ColumnModel.class);
                when(oldCol.getColumnName()).thenReturn("name_old");
                v.visitAddedColumn(newCol);
                v.visitModifiedColumn(newCol, oldCol);
            }
            return null;
        }).when(diff).tableContentAccept(any(), eq(DiffResult.TableContentPhase.ALTER));

        doAnswer(inv -> {
            TableContentVisitor v = inv.getArgument(0);
            DiffResult.TableContentPhase phase = inv.getArgument(1);
            if (phase == DiffResult.TableContentPhase.FK_ADD) {
                var rel = mock(RelationshipModel.class);
                when(rel.getConstraintName()).thenReturn("fk_user_role");
                v.visitAddedRelationship(rel);
            }
            return null;
        }).when(diff).tableContentAccept(any(), eq(DiffResult.TableContentPhase.FK_ADD));

        // tableAccept: DROPPED/RENAMED/ADDED 각각 호출 시 녹화형 비지터에 visit* 호출
        doAnswer(inv -> {
            TableVisitor v = inv.getArgument(0);
            DiffResult.TablePhase phase = inv.getArgument(1);
            if (phase == DiffResult.TablePhase.DROPPED) {
                var t = mock(EntityModel.class);
                when(t.getTableName()).thenReturn("old_table");
                v.visitDroppedTable(t);
            } else if (phase == DiffResult.TablePhase.RENAMED) {
                var r = mock(DiffResult.RenamedTable.class);
                EntityModel oldE = mock(EntityModel.class);
                when(oldE.getTableName()).thenReturn("old_name");
                when(r.getOldEntity()).thenReturn(oldE);

                EntityModel newE = mock(EntityModel.class);
                when(newE.getTableName()).thenReturn("new_name");
                when(r.getNewEntity()).thenReturn(newE);
                v.visitRenamedTable(r);
            } else if (phase == DiffResult.TablePhase.ADDED) {
                var t = mock(EntityModel.class);
                when(t.getTableName()).thenReturn("new_table");
                v.visitAddedTable(t);
            }
            return null;
        }).when(diff).tableAccept(any(), any());

        // sequence/tableGenerator accept는 호출만 검증(출력 영향 없음)
        doNothing().when(diff).sequenceAccept(any(), any(), any());
        doNothing().when(diff).tableGeneratorAccept(any(), any(), any());

        // --- given: VisitorProviders (녹화형 비지터) 주입을 위한 VisitorFactory static mocking ---
        Supplier<TableVisitor> tvSupplier = RecordingTableVisitor::new;
        Function<DiffResult.ModifiedEntity, TableContentVisitor> tcvFactory = me2 -> new RecordingTableContentVisitor();
        Function<EntityModel, TableContentVisitor> tcevFactory = me2 -> new RecordingEntityTableContentVisitor();

        var providers = new VisitorProviders(
                tvSupplier,
                tcvFactory,
                tcevFactory,
                Optional.of(() -> new SequenceVisitor() {
                    @Override
                    public void visitAddedSequence(SequenceModel sequence) {

                    }

                    @Override
                    public void visitDroppedSequence(SequenceModel sequence) {

                    }

                    @Override
                    public void visitModifiedSequence(SequenceModel newSequence, SequenceModel oldSequence) {

                    }

                    @Override
                    public String getGeneratedSql() {
                        return "";
                    }
                }), // sequence present
                Optional.of(() -> new TableGeneratorVisitor() {
                    @Override
                    public void visitAddedTableGenerator(TableGeneratorModel tableGenerator) {

                    }

                    @Override
                    public void visitDroppedTableGenerator(TableGeneratorModel tableGenerator) {

                    }

                    @Override
                    public void visitModifiedTableGenerator(TableGeneratorModel newTableGenerator, TableGeneratorModel oldTableGenerator) {

                    }

                    @Override
                    public String getGeneratedSql() {
                        return "";
                    }
                })  // tableGenerator present
        );

        try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
            vf.when(() -> VisitorFactory.forBundle(bundle)).thenReturn(providers);

            // --- when ---
            MigrationGenerator gen = new MigrationGenerator(bundle, SchemaModel.builder().build(), true);
            String sql = gen.generateSql(diff);

            // --- then: 순서대로 기대 문자열 조립 ---
            String expected = String.join("\n",
                    "-- WARNING: this is rollback SQL for a migration",
                    "-- WARNING: W1",
                    "-- WARNING: W2",
                    // 1-1 DROP (modified)
                    "ALTER TABLE DROP COLUMN \"age\"",
                    "ALTER TABLE DROP PRIMARY KEY",
                    // 1-2 DROPPED/RENAMED (tables)
                    "DROP TABLE \"old_table\"",
                    "RENAME TABLE \"old_name\" TO \"new_name\"",
                    // 2-1 ADDED (tables)
                    "CREATE TABLE \"new_table\" ()",
                    // 2-2 ALTER (modified)
                    "ALTER TABLE ADD COLUMN \"name\"",
                    "ALTER TABLE MODIFY COLUMN \"name\" /* from \"name_old\" */",
                    // 3 FK_ADD (modified)
                    "/* ADD RELATIONSHIP \"fk_user_role\" */"
            );
            assertEquals(expected, sql);

            // sequence/tableGenerator accept 호출 검증
            verify(diff, times(1)).sequenceAccept(any(), eq(DiffResult.SequenceDiff.Type.ADDED), eq(DiffResult.SequenceDiff.Type.MODIFIED));
            verify(diff, times(1)).sequenceAccept(any(), eq(DiffResult.SequenceDiff.Type.DROPPED));
            verify(diff, times(1)).tableGeneratorAccept(any(), eq(DiffResult.TableGeneratorDiff.Type.ADDED), eq(DiffResult.TableGeneratorDiff.Type.MODIFIED));
            verify(diff, times(1)).tableGeneratorAccept(any(), eq(DiffResult.TableGeneratorDiff.Type.DROPPED));
        }
    }

    @Test
    @DisplayName("변경 없음 + reverse=false → 경고 없으면 빈 문자열")
    void generateSql_no_changes_no_warnings() {
        DdlDialect ddl = mock(DdlDialect.class);
        DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

        DiffResult diff = mock(DiffResult.class);
        when(diff.getWarnings()).thenReturn(List.of());
        when(diff.getModifiedTables()).thenReturn(List.of());
        doNothing().when(diff).tableAccept(any(), any());
        doNothing().when(diff).tableContentAccept(any(), any());
        doNothing().when(diff).sequenceAccept(any(), any(), any());
        doNothing().when(diff).tableGeneratorAccept(any(), any(), any());

        var providers = new VisitorProviders(
                RecordingTableVisitor::new,
                me -> new RecordingTableContentVisitor(),
                me -> new RecordingEntityTableContentVisitor(),
                Optional.empty(),
                Optional.empty()
        );

        try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
            vf.when(() -> VisitorFactory.forBundle(bundle)).thenReturn(providers);

            MigrationGenerator gen = new MigrationGenerator(bundle, SchemaModel.builder().build(), false);
            String sql = gen.generateSql(diff);

            assertEquals("", sql);
        }
    }
}
