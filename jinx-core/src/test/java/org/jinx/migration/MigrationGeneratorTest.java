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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationGeneratorTest {

    @Test
    @DisplayName("reverse + warnings + DROP + ADD(rename→drop+create 포함) + ALTER + FK_ADD 순서대로 SQL이 조립된다")
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

        // DROPPED: MigrationGenerator가 getDroppedTables()를 직접 호출하여 DependencyResolver에 전달
        EntityModel droppedTable = mock(EntityModel.class);
        when(droppedTable.getTableName()).thenReturn("old_table");
        when(droppedTable.getRelationships()).thenReturn(Map.of());
        when(diff.getDroppedTables()).thenReturn(List.of(droppedTable));

        // ADDED: MigrationGenerator가 getAddedTables()를 직접 호출하여 DependencyResolver에 전달
        EntityModel addedTable = mock(EntityModel.class);
        when(addedTable.getTableName()).thenReturn("new_table");
        when(addedTable.getRelationships()).thenReturn(Map.of());
        when(diff.getAddedTables()).thenReturn(List.of(addedTable));

        // ModifiedEntity.accept: Phase 별로 녹화형 비지터에 visit* 호출 → SQL 축적
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
        }).when(me).accept(any(), eq(DiffResult.TableContentPhase.DROP));

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
        }).when(me).accept(any(), eq(DiffResult.TableContentPhase.ALTER));

        doAnswer(inv -> {
            TableContentVisitor v = inv.getArgument(0);
            DiffResult.TableContentPhase phase = inv.getArgument(1);
            if (phase == DiffResult.TableContentPhase.FK_ADD) {
                var rel = mock(RelationshipModel.class);
                when(rel.getConstraintName()).thenReturn("fk_user_role");
                v.visitAddedRelationship(rel);
            }
            return null;
        }).when(me).accept(any(), eq(DiffResult.TableContentPhase.FK_ADD));

        // RENAMED → DROP old + CREATE new 로 처리: getRenamedTables()를 직접 호출
        EntityModel oldNameEntity = mock(EntityModel.class);
        when(oldNameEntity.getTableName()).thenReturn("old_name");
        when(oldNameEntity.getRelationships()).thenReturn(Map.of());
        EntityModel newNameEntity = mock(EntityModel.class);
        when(newNameEntity.getTableName()).thenReturn("new_name");
        when(newNameEntity.getRelationships()).thenReturn(Map.of());
        DiffResult.RenamedTable renamedTable = mock(DiffResult.RenamedTable.class);
        when(renamedTable.getOldEntity()).thenReturn(oldNameEntity);
        when(renamedTable.getNewEntity()).thenReturn(newNameEntity);
        when(diff.getRenamedTables()).thenReturn(List.of(renamedTable));

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
            // 1-2 DROP: [droppedTables + renamed.oldEntity] → sortByFkDependency → reversed
            //   입력 순서: [old_table, old_name], FK 없음 → sorted 동일, reversed → [old_name, old_table]
            // 2-1 ADD: [addedTables + renamed.newEntity] → sortByFkDependency
            //   입력 순서: [new_table, new_name], FK 없음 → [new_table, new_name]
            String expected = String.join("\n",
                    "-- WARNING: this is rollback SQL for a migration",
                    "-- WARNING: W1",
                    "-- WARNING: W2",
                    // 1-1 DROP (modified)
                    "ALTER TABLE DROP COLUMN \"age\"",
                    "ALTER TABLE DROP PRIMARY KEY",
                    // 1-2 DROPPED (droppedTables + renamed old entity, reversed)
                    "DROP TABLE \"old_name\"",
                    "DROP TABLE \"old_table\"",
                    // 2-1 ADDED (addedTables + renamed new entity)
                    "CREATE TABLE \"new_table\" ()",
                    "CREATE TABLE \"new_name\" ()",
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

    @Nested
    @DisplayName("DependencyResolver 적용: FK 의존성 기반 테이블 순서")
    class FkDependencyOrdering {

        private VisitorProviders minimalProviders() {
            return new VisitorProviders(
                    RecordingTableVisitor::new,
                    me -> new RecordingTableContentVisitor(),
                    me -> new RecordingEntityTableContentVisitor(),
                    Optional.empty(),
                    Optional.empty()
            );
        }

        @Test
        @DisplayName("addedTables: 자식(A)이 먼저 입력되어도 부모(B)가 먼저 CREATE된다")
        void create_order_parent_before_child() {
            EntityModel tableB = EntityModel.builder().tableName("table_b").build();
            EntityModel tableA = EntityModel.builder()
                    .tableName("table_a")
                    .relationships(Map.of("fk_a_b", RelationshipModel.builder()
                            .constraintName("fk_a_b").referencedTable("table_b").build()))
                    .build();

            // 의도적으로 자식(A) 먼저 입력 — DependencyResolver가 B, A 순으로 정렬해야 함
            DiffResult diff = DiffResult.builder()
                    .addedTables(new ArrayList<>(List.of(tableA, tableB)))
                    .droppedTables(new ArrayList<>())
                    .build();

            DdlDialect ddl = mock(DdlDialect.class);
            DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

            try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
                vf.when(() -> VisitorFactory.forBundle(bundle)).thenReturn(minimalProviders());

                String sql = new MigrationGenerator(bundle, SchemaModel.builder().build(), false)
                        .generateSql(diff);

                int posB = sql.indexOf("CREATE TABLE \"table_b\"");
                int posA = sql.indexOf("CREATE TABLE \"table_a\"");
                assertThat(posB).isGreaterThanOrEqualTo(0);
                assertThat(posB).isLessThan(posA);
            }
        }

        @Test
        @DisplayName("droppedTables: 부모(B)가 먼저 입력되어도 자식(A)이 먼저 DROP된다")
        void drop_order_child_before_parent() {
            EntityModel tableB = EntityModel.builder().tableName("table_b").build();
            EntityModel tableA = EntityModel.builder()
                    .tableName("table_a")
                    .relationships(Map.of("fk_a_b", RelationshipModel.builder()
                            .constraintName("fk_a_b").referencedTable("table_b").build()))
                    .build();

            // 의도적으로 부모(B) 먼저 입력 — DependencyResolver.reversed()가 A, B 순으로 정렬해야 함
            DiffResult diff = DiffResult.builder()
                    .addedTables(new ArrayList<>())
                    .droppedTables(new ArrayList<>(List.of(tableB, tableA)))
                    .build();

            DdlDialect ddl = mock(DdlDialect.class);
            DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

            try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
                vf.when(() -> VisitorFactory.forBundle(bundle)).thenReturn(minimalProviders());

                String sql = new MigrationGenerator(bundle, SchemaModel.builder().build(), false)
                        .generateSql(diff);

                int posA = sql.indexOf("DROP TABLE \"table_a\"");
                int posB = sql.indexOf("DROP TABLE \"table_b\"");
                assertThat(posA).isGreaterThanOrEqualTo(0);
                assertThat(posA).isLessThan(posB);
            }
        }

        @Test
        @DisplayName("선형 체인 A→B→C: CREATE 순서는 C, B, A")
        void create_order_linear_chain() {
            EntityModel tableC = EntityModel.builder().tableName("table_c").build();
            EntityModel tableB = EntityModel.builder()
                    .tableName("table_b")
                    .relationships(Map.of("fk_b_c", RelationshipModel.builder()
                            .constraintName("fk_b_c").referencedTable("table_c").build()))
                    .build();
            EntityModel tableA = EntityModel.builder()
                    .tableName("table_a")
                    .relationships(Map.of("fk_a_b", RelationshipModel.builder()
                            .constraintName("fk_a_b").referencedTable("table_b").build()))
                    .build();

            // 의도적으로 자식 우선 입력 [A, B, C] — 정렬 후 [C, B, A] 이어야 함
            DiffResult diff = DiffResult.builder()
                    .addedTables(new ArrayList<>(List.of(tableA, tableB, tableC)))
                    .droppedTables(new ArrayList<>())
                    .build();

            DdlDialect ddl = mock(DdlDialect.class);
            DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

            try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
                vf.when(() -> VisitorFactory.forBundle(bundle)).thenReturn(minimalProviders());

                String sql = new MigrationGenerator(bundle, SchemaModel.builder().build(), false)
                        .generateSql(diff);

                int posC = sql.indexOf("CREATE TABLE \"table_c\"");
                int posB = sql.indexOf("CREATE TABLE \"table_b\"");
                int posA = sql.indexOf("CREATE TABLE \"table_a\"");
                assertThat(posC).isLessThan(posB);
                assertThat(posB).isLessThan(posA);
            }
        }

        @Test
        @DisplayName("선형 체인 DROP: C→B→A 역순 입력이어도 DROP 순서는 A, B, C")
        void drop_order_linear_chain() {
            EntityModel tableC = EntityModel.builder().tableName("table_c").build();
            EntityModel tableB = EntityModel.builder()
                    .tableName("table_b")
                    .relationships(Map.of("fk_b_c", RelationshipModel.builder()
                            .constraintName("fk_b_c").referencedTable("table_c").build()))
                    .build();
            EntityModel tableA = EntityModel.builder()
                    .tableName("table_a")
                    .relationships(Map.of("fk_a_b", RelationshipModel.builder()
                            .constraintName("fk_a_b").referencedTable("table_b").build()))
                    .build();

            // 의도적으로 부모 우선 입력 [C, B, A] — reversed() 후 [A, B, C] 이어야 함
            DiffResult diff = DiffResult.builder()
                    .addedTables(new ArrayList<>())
                    .droppedTables(new ArrayList<>(List.of(tableC, tableB, tableA)))
                    .build();

            DdlDialect ddl = mock(DdlDialect.class);
            DialectBundle bundle = DialectBundle.builder(ddl, DatabaseType.MYSQL).build();

            try (MockedStatic<VisitorFactory> vf = mockStatic(VisitorFactory.class)) {
                vf.when(() -> VisitorFactory.forBundle(bundle)).thenReturn(minimalProviders());

                String sql = new MigrationGenerator(bundle, SchemaModel.builder().build(), false)
                        .generateSql(diff);

                int posA = sql.indexOf("DROP TABLE \"table_a\"");
                int posB = sql.indexOf("DROP TABLE \"table_b\"");
                int posC = sql.indexOf("DROP TABLE \"table_c\"");
                assertThat(posA).isLessThan(posB);
                assertThat(posB).isLessThan(posC);
            }
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
        when(diff.getAddedTables()).thenReturn(List.of());
        when(diff.getDroppedTables()).thenReturn(List.of());
        when(diff.getRenamedTables()).thenReturn(List.of());
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
