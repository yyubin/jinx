package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.*;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiquibaseVisitorExtraTest {

    private DialectBundle bundle;           // mock
    private ChangeSetIdGenerator idGen;     // simple stub
    private LiquibaseVisitor visitor;

    @BeforeEach
    void setUp() {
        bundle = mock(DialectBundle.class);

        // 기본은 liquibase() Optional.empty() — 개별 테스트에서 필요시 덮어씀
        when(bundle.liquibase()).thenReturn(Optional.empty());

        // withSequence / withTableGenerator 는 넘긴 Consumer 를 즉시 실행
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Object> c = (Consumer<Object>) inv.getArgument(0);
            c.accept(null); // 내부에서 dialect를 사용하지 않으므로 null로 충분
            return null;
        }).when(bundle).withSequence(any());

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Object> c = (Consumer<Object>) inv.getArgument(0);
            c.accept(null);
            return null;
        }).when(bundle).withTableGenerator(any());

        idGen = new ChangeSetIdGenerator() {
            private long n = 0;
            @Override public String nextId() { return "cs-extra-" + (++n); }
        };

        visitor = new LiquibaseVisitor(bundle, idGen);
    }


    @Test
    @DisplayName("visitAddedTable: PK 없음 분기 커버(Map<ColumnKey, ColumnModel>)")
    void visitAddedTable_noPk_branch() {
        visitor.setCurrentTableName(null);

        ColumnModel id = mock(ColumnModel.class);
        when(id.getColumnName()).thenReturn("id");
        when(id.isPrimaryKey()).thenReturn(false);
        when(id.getSqlTypeOverride()).thenReturn("BIGINT");
        when(id.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);

        ColumnModel name = mock(ColumnModel.class);
        when(name.getColumnName()).thenReturn("name");
        when(name.isPrimaryKey()).thenReturn(false);
        when(name.getSqlTypeOverride()).thenReturn("VARCHAR(50)");
        when(name.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);

        Map<ColumnKey, ColumnModel> cols = new LinkedHashMap<>();
        cols.put(ColumnKey.of("id","id"), id);
        cols.put(ColumnKey.of("name","name"), name);

        EntityModel table = mock(EntityModel.class);
        when(table.getTableName()).thenReturn("users");
        when(table.getColumns()).thenReturn(cols);
        when(table.getConstraints()).thenReturn(Collections.emptyMap());
        when(table.getIndexes()).thenReturn(Collections.emptyMap());
        when(table.getRelationships()).thenReturn(Collections.emptyMap());

        int before = visitor.getChangeSets().size();
        visitor.visitAddedTable(table);
        int after = visitor.getChangeSets().size();

        // PK가 없으므로 CreateTable 만 생성
        assertEquals(before + 1, after);
    }

    @Test
    @DisplayName("visitDroppedTable: DropTable changeSet 생성")
    void visitDroppedTable_addsChangeSet() {
        EntityModel table = mock(EntityModel.class);
        when(table.getTableName()).thenReturn("users");

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedTable(table);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitRenamedTable: RenameTable changeSet 생성")
    void visitRenamedTable_addsChangeSet() {
        EntityModel oldE = mock(EntityModel.class);
        when(oldE.getTableName()).thenReturn("old_t");
        EntityModel newE = mock(EntityModel.class);
        when(newE.getTableName()).thenReturn("new_t");
        DiffResult.RenamedTable renamed = new DiffResult.RenamedTable(oldE, newE, "");

        int before = visitor.getChangeSets().size();
        visitor.visitRenamedTable(renamed);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    // ===== 시퀀스 =====

    @Test
    @DisplayName("visitAddedSequence: CreateSequence changeSet 생성")
    void visitAddedSequence_addsChangeSet() {
        SequenceModel seq = mock(SequenceModel.class);
        when(seq.getName()).thenReturn("seq1");
        when(seq.getInitialValue()).thenReturn(10L);
        when(seq.getAllocationSize()).thenReturn(Integer.valueOf(100));

        int before = visitor.getChangeSets().size();
        visitor.visitAddedSequence(seq);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitDroppedSequence: DropSequence changeSet 생성")
    void visitDroppedSequence_addsChangeSet() {
        SequenceModel seq = mock(SequenceModel.class);
        when(seq.getName()).thenReturn("seq1");

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedSequence(seq);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitModifiedSequence: Drop → Create 두 개의 changeSet 생성")
    void visitModifiedSequence_addsTwoChangeSets() {
        SequenceModel oldS = mock(SequenceModel.class);
        when(oldS.getName()).thenReturn("seq_old");

        SequenceModel newS = mock(SequenceModel.class);
        when(newS.getName()).thenReturn("seq_new");
        when(newS.getInitialValue()).thenReturn(1L);
        when(newS.getAllocationSize()).thenReturn(Integer.valueOf(50));

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedSequence(newS, oldS);
        assertEquals(before + 2, visitor.getChangeSets().size());
    }

    // ===== 테이블 제너레이터 =====

    @Test
    @DisplayName("visitAddedTableGenerator: CreateTable + Insert changeSet 생성 (Liquibase 타입 지정)")
    void visitAddedTableGenerator_addsChangeSets() {
        // LiquibaseDialect가 TableGenerator 컬럼 타입을 제공하도록 스텁
        var lb = mock(org.jinx.migration.spi.dialect.LiquibaseDialect.class);
        when(lb.getTableGeneratorPkColumnType()).thenReturn("VARCHAR(128)");
        when(lb.getTableGeneratorValueColumnType()).thenReturn("BIGINT");
        when(bundle.liquibase()).thenReturn(Optional.of(lb));

        TableGeneratorModel tg = mock(TableGeneratorModel.class);
        when(tg.getTable()).thenReturn("seq_table");
        when(tg.getPkColumnName()).thenReturn("k");
        when(tg.getValueColumnName()).thenReturn("v");
        when(tg.getPkColumnValue()).thenReturn("SEQ_A");
        when(tg.getInitialValue()).thenReturn(1L);

        int before = visitor.getChangeSets().size();
        visitor.visitAddedTableGenerator(tg);
        // CreateTable + Insert
        assertEquals(before + 2, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitDroppedTableGenerator: Drop changeSet 생성")
    void visitDroppedTableGenerator_addsChangeSet() {
        TableGeneratorModel tg = mock(TableGeneratorModel.class);
        when(tg.getTable()).thenReturn("seq_table");

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedTableGenerator(tg);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitModifiedTableGenerator: Drop → Create 두 개의 changeSet 생성")
    void visitModifiedTableGenerator_addsTwoChangeSets() {
        var lb = mock(org.jinx.migration.spi.dialect.LiquibaseDialect.class);
        when(lb.getTableGeneratorPkColumnType()).thenReturn("VARCHAR(64)");
        when(lb.getTableGeneratorValueColumnType()).thenReturn("BIGINT");
        when(bundle.liquibase()).thenReturn(Optional.of(lb));

        TableGeneratorModel oldTg = mock(TableGeneratorModel.class);
        when(oldTg.getTable()).thenReturn("old_seq");
        when(oldTg.getPkColumnName()).thenReturn("k");
        when(oldTg.getValueColumnName()).thenReturn("v");

        TableGeneratorModel newTg = mock(TableGeneratorModel.class);
        when(newTg.getTable()).thenReturn("new_seq");
        when(newTg.getPkColumnName()).thenReturn("k");
        when(newTg.getValueColumnName()).thenReturn("v");

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedTableGenerator(newTg, oldTg);
        assertEquals(before + 2, visitor.getChangeSets().size());
    }

    // ===== 컬럼 컨텐츠 =====

    @Test
    @DisplayName("visitDroppedColumn: DropColumn changeSet 생성")
    void visitDroppedColumn_addsChangeSet() {
        visitor.setCurrentTableName("users");
        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn("name");

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedColumn(c);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitModifiedColumn: 타입 변경 분기")
    void visitModifiedColumn_typeChange() {
        visitor.setCurrentTableName("users");

        ColumnModel oldC = mock(ColumnModel.class);
        when(oldC.getColumnName()).thenReturn("age");
        when(oldC.getSqlTypeOverride()).thenReturn("INT");
        when(oldC.isNullable()).thenReturn(true);

        ColumnModel newC = mock(ColumnModel.class);
        when(newC.getColumnName()).thenReturn("age");
        when(newC.getSqlTypeOverride()).thenReturn("BIGINT"); // 타입 변경
        when(newC.isNullable()).thenReturn(true);

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedColumn(newC, oldC);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitModifiedColumn: NULLABLE true → false (AddNotNull)")
    void visitModifiedColumn_addNotNull() {
        visitor.setCurrentTableName("users");

        ColumnModel oldC = mock(ColumnModel.class);
        when(oldC.getColumnName()).thenReturn("title");
        when(oldC.getSqlTypeOverride()).thenReturn("VARCHAR(100)");
        when(oldC.isNullable()).thenReturn(true);

        ColumnModel newC = mock(ColumnModel.class);
        when(newC.getColumnName()).thenReturn("title");
        when(newC.getSqlTypeOverride()).thenReturn("VARCHAR(100)");
        when(newC.isNullable()).thenReturn(false);

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedColumn(newC, oldC);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitModifiedColumn: NULLABLE false → true (DropNotNull)")
    void visitModifiedColumn_dropNotNull() {
        visitor.setCurrentTableName("users");

        ColumnModel oldC = mock(ColumnModel.class);
        when(oldC.getColumnName()).thenReturn("note");
        when(oldC.getSqlTypeOverride()).thenReturn("VARCHAR(200)");
        when(oldC.isNullable()).thenReturn(false);

        ColumnModel newC = mock(ColumnModel.class);
        when(newC.getColumnName()).thenReturn("note");
        when(newC.getSqlTypeOverride()).thenReturn("VARCHAR(200)");
        when(newC.isNullable()).thenReturn(true);

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedColumn(newC, oldC);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitRenamedColumn: RenameColumn changeSet 생성")
    void visitRenamedColumn_addsChangeSet() {
        visitor.setCurrentTableName("users");

        ColumnModel oldC = mock(ColumnModel.class);
        when(oldC.getColumnName()).thenReturn("old");
        ColumnModel newC = mock(ColumnModel.class);
        when(newC.getColumnName()).thenReturn("new");

        int before = visitor.getChangeSets().size();
        visitor.visitRenamedColumn(newC, oldC);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    // ===== 인덱스 =====

    @Test
    @DisplayName("visitModifiedIndex: DropIndex → CreateIndex 두 개 changeSet 생성")
    void visitModifiedIndex_addsTwoChangeSets() {
        // old
        IndexModel oldIdx = mock(IndexModel.class);
        when(oldIdx.getIndexName()).thenReturn("ix_old");
        when(oldIdx.getTableName()).thenReturn("users");

        // new
        IndexModel newIdx = mock(IndexModel.class);
        when(newIdx.getIndexName()).thenReturn("ix_new");
        when(newIdx.getTableName()).thenReturn("users");
        when(newIdx.getColumnNames()).thenReturn(List.of("email"));

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedIndex(newIdx, oldIdx);
        assertEquals(before + 2, visitor.getChangeSets().size());
    }

    // ===== 제약 =====

    @Test
    @DisplayName("visitDroppedConstraint: UNIQUE 이름 명시 분기")
    void visitDroppedConstraint_unique_named() {
        visitor.setCurrentTableName("users");
        ConstraintModel uq = mock(ConstraintModel.class);
        when(uq.getType()).thenReturn(ConstraintType.UNIQUE);
        when(uq.getName()).thenReturn("uq_users_email");
        when(uq.getColumns()).thenReturn(List.of("email"));

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedConstraint(uq);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitDroppedConstraint: CHECK 이름 명시 분기")
    void visitDroppedConstraint_check_named() {
        visitor.setCurrentTableName("orders");
        ConstraintModel ck = mock(ConstraintModel.class);
        when(ck.getType()).thenReturn(ConstraintType.CHECK);
        when(ck.getName()).thenReturn("ck_orders_total");
        when(ck.getColumns()).thenReturn(List.of("total"));
        when(ck.getCheckClause()).thenReturn(Optional.of("total >= 0"));

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedConstraint(ck);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitModifiedConstraint: drop → add 흐름")
    void visitModifiedConstraint_flow() {
        visitor.setCurrentTableName("users");

        ConstraintModel oldC = mock(ConstraintModel.class);
        when(oldC.getType()).thenReturn(ConstraintType.UNIQUE);
        when(oldC.getName()).thenReturn("uq_users_email");
        when(oldC.getColumns()).thenReturn(List.of("email"));

        ConstraintModel newC = mock(ConstraintModel.class);
        when(newC.getType()).thenReturn(ConstraintType.UNIQUE);
        when(newC.getName()).thenReturn("uq_users_email_new");
        when(newC.getColumns()).thenReturn(List.of("email"));

        int before = visitor.getChangeSets().size();
        visitor.visitModifiedConstraint(newC, oldC);
        assertEquals(before + 2, visitor.getChangeSets().size());
    }

    // ===== FK =====

    @Test
    @DisplayName("visitAddedRelationship: constraintName 명시 + onDelete/onUpdate null 분기")
    void visitAddedRelationship_named_withoutActions() {
        visitor.setCurrentTableName("orders");

        RelationshipModel rel = mock(RelationshipModel.class);
        when(rel.isNoConstraint()).thenReturn(false);
        when(rel.getTableName()).thenReturn("orders");
        when(rel.getConstraintName()).thenReturn("fk_orders_user_id_users");
        when(rel.getColumns()).thenReturn(List.of("user_id"));
        when(rel.getReferencedTable()).thenReturn("users");
        when(rel.getReferencedColumns()).thenReturn(List.of("id"));
        when(rel.getOnDelete()).thenReturn(null);
        when(rel.getOnUpdate()).thenReturn(null);

        int before = visitor.getChangeSets().size();
        visitor.visitAddedRelationship(rel);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("visitDroppedRelationship: NO_CONSTRAINT=true는 skip")
    void visitDroppedRelationship_skipWhenNoConstraint() {
        visitor.setCurrentTableName("orders");
        RelationshipModel rel = mock(RelationshipModel.class);
        when(rel.isNoConstraint()).thenReturn(true);

        int before = visitor.getChangeSets().size();
        visitor.visitDroppedRelationship(rel);
        assertEquals(before, visitor.getChangeSets().size());
    }

    // ===== 보조/분기 메서드 커버 =====

    @Test
    @DisplayName("getTableNameSafely: currentTableName이 공백이면 예외")
    void getTableNameSafely_blank_throws() {
        visitor.setCurrentTableName("  "); // 공백만
        IndexModel idx = mock(IndexModel.class);
        when(idx.getTableName()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> visitor.visitAddedIndex(idx));
    }

    @Test
    @DisplayName("getLiquibaseTypeName: liquibase가 타입을 제공하는 분기")
    void liquibase_typeName_branch() {
        var lb = mock(org.jinx.migration.spi.dialect.LiquibaseDialect.class);
        when(lb.getLiquibaseTypeName(any())).thenReturn("NUMERIC(10)");
        when(lb.shouldUseAutoIncrement(GenerationStrategy.AUTO)).thenReturn(true);
        when(lb.getUuidDefaultValue()).thenReturn("UUID()");
        when(bundle.liquibase()).thenReturn(Optional.of(lb));

        visitor.setCurrentTableName("users");

        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn("code");
        when(c.getSqlTypeOverride()).thenReturn(null); // liquibase 분기로 유도
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.AUTO); // shouldUseAutoIncrement 분기도 커버
        when(c.isNullable()).thenReturn(true);
        when(c.getDefaultValue()).thenReturn("42");

        int before = visitor.getChangeSets().size();
        visitor.visitAddedColumn(c);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("setDefaultValueWithPriority: UUID → computed default 분기")
    void defaultPriority_uuidComputed_branch() {
        var lb = mock(org.jinx.migration.spi.dialect.LiquibaseDialect.class);
        when(lb.getLiquibaseTypeName(any())).thenReturn("CHAR(36)");
        when(lb.getUuidDefaultValue()).thenReturn("UUID()");
        when(bundle.liquibase()).thenReturn(Optional.of(lb));

        visitor.setCurrentTableName("users");

        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn("uuid");
        when(c.getSqlTypeOverride()).thenReturn(null);
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.UUID);
        when(c.isNullable()).thenReturn(true);
        when(c.getDefaultValue()).thenReturn(null);

        int before = visitor.getChangeSets().size();
        visitor.visitAddedColumn(c);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("setDefaultValueWithPriority: SEQUENCE → sequence next 분기")
    void defaultPriority_sequence_branch() {
        var lb = mock(org.jinx.migration.spi.dialect.LiquibaseDialect.class);
        when(lb.getLiquibaseTypeName(any())).thenReturn("BIGINT");
        when(bundle.liquibase()).thenReturn(Optional.of(lb));

        visitor.setCurrentTableName("users");

        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn("seq_col");
        when(c.getSqlTypeOverride()).thenReturn(null);
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.SEQUENCE);
        when(c.getDefaultValue()).thenReturn("order_seq"); // 시퀀스 이름
        when(c.isNullable()).thenReturn(true);

        int before = visitor.getChangeSets().size();
        visitor.visitAddedColumn(c);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }

    @Test
    @DisplayName("setDefaultValueWithPriority: literal default 분기")
    void defaultPriority_literal_branch() {
        var lb = mock(org.jinx.migration.spi.dialect.LiquibaseDialect.class);
        when(lb.getLiquibaseTypeName(any())).thenReturn("INT");
        when(bundle.liquibase()).thenReturn(Optional.of(lb));

        visitor.setCurrentTableName("users");

        ColumnModel c = mock(ColumnModel.class);
        when(c.getColumnName()).thenReturn("flag");
        when(c.getSqlTypeOverride()).thenReturn(null);
        when(c.getGenerationStrategy()).thenReturn(GenerationStrategy.NONE);
        when(c.getDefaultValue()).thenReturn("0");
        when(c.isNullable()).thenReturn(true);

        int before = visitor.getChangeSets().size();
        visitor.visitAddedColumn(c);
        assertEquals(before + 1, visitor.getChangeSets().size());
    }
}
