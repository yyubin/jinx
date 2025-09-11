package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.DiffResult.RenamedTable;
import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchemaDifferTest {

    static class RecordingDiffer implements Differ {
        private final String name;
        RecordingDiffer(String name) { this.name = name; }
        @Override public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
            result.getWarnings().add("RUN:" + name);
        }
    }

    /** 예외를 던지는 Differ: 예외 격리 동작 검증용 */
    static class ThrowingDiffer implements Differ {
        private final String name;
        ThrowingDiffer(String name) { this.name = name; }
        @Override public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
            throw new IllegalStateException("boom@" + name);
        }
    }

    private SchemaModel emptySchema() {
        SchemaModel sm = mock(SchemaModel.class);
        when(sm.getEntities()).thenReturn(Map.of()); // 비어있는 맵 리턴
        return sm;
    }

    @Test
    void pipeline_runs_in_fixed_order() {
        SchemaModel oldSchema = emptySchema();
        SchemaModel newSchema = emptySchema();

        Differ d1 = new RecordingDiffer("TableDiffer");
        Differ d2 = new RecordingDiffer("EntityModificationDiffer");
        Differ d3 = new RecordingDiffer("SequenceDiffer");
        Differ d4 = new RecordingDiffer("TableGeneratorDiffer");

        SchemaDiffer differ = new SchemaDiffer(CaseNormalizer.lower(), List.of(d1, d2, d3, d4));

        DiffResult out = differ.diff(oldSchema, newSchema);

        // 순서 확인
        assertEquals(List.of("RUN:TableDiffer", "RUN:EntityModificationDiffer", "RUN:SequenceDiffer", "RUN:TableGeneratorDiffer"),
                out.getWarnings().subList(0, 4));
    }

    @Test
    void exceptions_from_one_differ_are_captured_and_do_not_block_others() {
        SchemaModel oldSchema = emptySchema();
        SchemaModel newSchema = emptySchema();

        Differ ok1 = new RecordingDiffer("A");
        Differ bad = new ThrowingDiffer("B");
        Differ ok2 = new RecordingDiffer("C");

        SchemaDiffer differ = new SchemaDiffer(CaseNormalizer.lower(), List.of(ok1, bad, ok2));

        DiffResult out = differ.diff(oldSchema, newSchema);

        // ok1 실행 흔적
        assertTrue(out.getWarnings().contains("RUN:A"));
        // 예외 경고 수집 확인
        assertTrue(out.getWarnings().stream().anyMatch(s ->
                s.startsWith("Differ failed: ThrowingDiffer") && s.contains("IllegalStateException") && s.contains("boom@B")));
        // ok2도 계속 실행되었는지 확인
        assertTrue(out.getWarnings().contains("RUN:C"));
    }

    @Test
    void renamed_pairs_trigger_entity_modification_diffPair() {
        // 스키마 준비 (엔티티는 비워도 됨)
        SchemaModel oldSchema = emptySchema();
        SchemaModel newSchema = emptySchema();

        // 1) TableDiffer 역할을 하는 mock: renamed pair를 결과에 추가
        Differ tableDiffer = (o, n, r) -> {
            EntityModel oldEntity = EntityModel.builder().entityName("OldE").tableName("t_old").build();
            EntityModel newEntity = EntityModel.builder().entityName("NewE").tableName("t_new").build();
            r.getRenamedTables().add(RenamedTable.builder()
                    .oldEntity(oldEntity)
                    .newEntity(newEntity)
                    .changeDetail("rename t_old -> t_new")
                    .build());
        };

        // 2) EntityModificationDiffer mock: diffPair 호출 여부 검증
        EntityModificationDiffer emd = mock(EntityModificationDiffer.class);

        // 3) 나머지 더미 differ들
        Differ seq = new RecordingDiffer("Seq");
        Differ gen = new RecordingDiffer("Gen");

        SchemaDiffer differ = new SchemaDiffer(CaseNormalizer.lower(), List.of(
                tableDiffer, emd, seq, gen
        ));

        DiffResult out = differ.diff(oldSchema, newSchema);

        // diffPair가 호출되었는지 캡처
        ArgumentCaptor<EntityModel> oldCap = ArgumentCaptor.forClass(EntityModel.class);
        ArgumentCaptor<EntityModel> newCap = ArgumentCaptor.forClass(EntityModel.class);
        ArgumentCaptor<DiffResult> resCap = ArgumentCaptor.forClass(DiffResult.class);

        verify(emd, atLeastOnce()).diffPair(oldCap.capture(), newCap.capture(), resCap.capture());

        assertEquals("OldE", oldCap.getValue().getEntityName());
        assertEquals("NewE", newCap.getValue().getEntityName());
        assertSame(out, resCap.getValue()); // 동일 result 전달 확인
    }

    @Test
    void diffPair_exception_is_captured_as_warning() {
        SchemaModel oldSchema = emptySchema();
        SchemaModel newSchema = emptySchema();

        // 리네임 쌍 추가하는 더미 TableDiffer
        Differ tableDiffer = (o, n, r) -> {
            EntityModel oldEntity = EntityModel.builder().entityName("OldE").tableName("t_old").build();
            EntityModel newEntity = EntityModel.builder().entityName("NewE").tableName("t_new").build();
            r.getRenamedTables().add(RenamedTable.builder()
                    .oldEntity(oldEntity)
                    .newEntity(newEntity)
                    .changeDetail("rename")
                    .build());
        };

        // diffPair 호출 시 예외를 던지는 EntityModificationDiffer mock
        EntityModificationDiffer emd = mock(EntityModificationDiffer.class);
        doThrow(new RuntimeException("oops")).when(emd).diffPair(any(), any(), any());

        SchemaDiffer differ = new SchemaDiffer(CaseNormalizer.lower(), List.of(
                tableDiffer, emd
        ));

        DiffResult out = differ.diff(oldSchema, newSchema);

        // 경고에 'Renamed pair diff failed' 포함 여부 확인
        assertTrue(out.getWarnings().stream().anyMatch(w ->
                w.startsWith("Renamed pair diff failed: OldE -> NewE") && w.contains("RuntimeException") && w.contains("oops")));
    }
}
