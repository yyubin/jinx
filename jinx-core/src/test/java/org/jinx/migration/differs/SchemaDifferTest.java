package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.DiffResult.RenamedTable;
import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.Test;

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

        // 순서 확인 (파이프라인 실행 순서가 경고에 기록됨)
        assertEquals(
                List.of("RUN:TableDiffer", "RUN:EntityModificationDiffer", "RUN:SequenceDiffer", "RUN:TableGeneratorDiffer"),
                out.getWarnings().subList(0, 4)
        );
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
    void renamed_pairs_trigger_entity_modification_effects() {
        // 스키마 준비 (엔티티 맵은 비워도 됨)
        SchemaModel oldSchema = emptySchema();
        SchemaModel newSchema = emptySchema();

        // 1) TableDiffer 역할을 하는 더미 differ: renamed pair를 결과에 추가
        Differ tableDiffer = (o, n, r) -> {
            // diffPair가 실제로 경고/수정사항을 남기도록 old/new에 차이를 만들어 둠 (schema 변경)
            EntityModel oldEntity = EntityModel.builder()
                    .entityName("OldE")
                    .tableName("t_old")
                    .schema("s1")
                    .build();
            EntityModel newEntity = EntityModel.builder()
                    .entityName("NewE")
                    .tableName("t_new")
                    .schema("s2") // 변경 발생
                    .build();
            r.getRenamedTables().add(RenamedTable.builder()
                    .oldEntity(oldEntity)
                    .newEntity(newEntity)
                    .changeDetail("rename t_old -> t_new")
                    .build());
        };

        // 2) 나머지 더미 differ들 (파이프라인 흐름 확인용)
        Differ seq = new RecordingDiffer("Seq");
        Differ gen = new RecordingDiffer("Gen");

        // 주의: 이제 SchemaDiffer는 내부적으로 항상 동일 normalizer로 EntityModificationDiffer를 생성함
        SchemaDiffer differ = new SchemaDiffer(CaseNormalizer.lower(), List.of(
                tableDiffer, seq, gen
        ));

        DiffResult out = differ.diff(oldSchema, newSchema);

        // rename 쌍 자체는 추가되었어야 함
        assertEquals(1, out.getRenamedTables().size());

        // diffPair가 실행되어 수정사항/경고가 반영되었는지 관측 가능한 효과로 검증
        // - ModifiedTables에 엔트리가 생겼거나
        // - 경고에 "Schema changed" 메시지가 포함되어야 함
        boolean hasSchemaChangedWarning = out.getWarnings().stream()
                .anyMatch(w -> w.startsWith("Schema changed: s1") && w.contains("→ s2") && w.contains("(entity=NewE)"));

        boolean hasModifiedEntry = !out.getModifiedTables().isEmpty()
                && "OldE".equals(out.getModifiedTables().get(0).getOldEntity().getEntityName())
                && "NewE".equals(out.getModifiedTables().get(0).getNewEntity().getEntityName());

        assertTrue(hasSchemaChangedWarning || hasModifiedEntry,
                "diffPair must have produced either a schema-change warning or a modified entry");
    }
}
