package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.jinx.model.SequenceModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SequenceDifferTest {

    private SequenceDiffer sequenceDiffer;

    @BeforeEach
    void setUp() {
        sequenceDiffer = new SequenceDiffer();
    }

    @Test
    @DisplayName("시퀀스 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenSequencesAreIdentical() {
        SequenceModel seq1 = createSequence("user_seq", 1, 50);
        SchemaModel oldSchema = createSchema(seq1);
        SchemaModel newSchema = createSchema(seq1);
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertTrue(result.getSequenceDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 시퀀스가 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedSequence() {
        SequenceModel seq1 = createSequence("user_seq", 1, 50);
        SchemaModel oldSchema = createSchema(); // Empty schema
        SchemaModel newSchema = createSchema(seq1);
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getSequenceDiffs().size());
        DiffResult.SequenceDiff diff = result.getSequenceDiffs().get(0);
        assertEquals(DiffResult.SequenceDiff.Type.ADDED, diff.getType());
        assertEquals("user_seq", diff.getSequence().getName());
    }

    @Test
    @DisplayName("기존 시퀀스가 삭제되었을 때 'DROPPED'로 감지해야 함")
    void shouldDetectDroppedSequence() {
        SequenceModel seq1 = createSequence("user_seq", 1, 50);
        SchemaModel oldSchema = createSchema(seq1);
        SchemaModel newSchema = createSchema(); // Empty schema
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getSequenceDiffs().size());
        DiffResult.SequenceDiff diff = result.getSequenceDiffs().get(0);
        assertEquals(DiffResult.SequenceDiff.Type.DROPPED, diff.getType());
        assertEquals("user_seq", diff.getSequence().getName());
    }

    @Test
    @DisplayName("시퀀스 속성이 변경되었을 때 'MODIFIED'로 감지하고 상세 내역을 생성해야 함")
    void shouldDetectModifiedSequence_withChangeDetail() {
        SequenceModel oldSeq = createSequence("user_seq", 1, 50);
        SequenceModel newSeq = createSequence("user_seq", 100, 25); // initialValue와 allocationSize 변경
        SchemaModel oldSchema = createSchema(oldSeq);
        SchemaModel newSchema = createSchema(newSeq);
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getSequenceDiffs().size());
        DiffResult.SequenceDiff diff = result.getSequenceDiffs().get(0);
        assertEquals(DiffResult.SequenceDiff.Type.MODIFIED, diff.getType());
        assertEquals("user_seq", diff.getSequence().getName());
        assertEquals(1, diff.getOldSequence().getInitialValue());
        assertEquals(100, diff.getSequence().getInitialValue());

        assertNotNull(diff.getChangeDetail());
        assertTrue(diff.getChangeDetail().contains("initialValue changed from 1 to 100"), "initialValue 변경 내역이 포함되어야 합니다.");
        assertTrue(diff.getChangeDetail().contains("allocationSize changed from 50 to 25"), "allocationSize 변경 내역이 포함되어야 합니다.");
    }

    @Test
    @DisplayName("cache, minValue, maxValue 변경 시 'MODIFIED'와 상세 내역 생성되어야 함")
    void shouldDetectChangesToCacheMinMaxValues() {
        SequenceModel oldSeq = createSequence("user_seq", 1, 50);
        oldSeq.setCache(20);
        oldSeq.setMinValue(1);
        oldSeq.setMaxValue(1000);

        SequenceModel newSeq = createSequence("user_seq", 1, 50);
        newSeq.setCache(30);           // 변경
        newSeq.setMinValue(5);         // 변경
        newSeq.setMaxValue(2000);      // 변경

        SchemaModel oldSchema = createSchema(oldSeq);
        SchemaModel newSchema = createSchema(newSeq);
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getSequenceDiffs().size());
        String detail = result.getSequenceDiffs().get(0).getChangeDetail();
        assertAll(
                () -> assertTrue(detail.contains("cache changed from 20 to 30")),
                () -> assertTrue(detail.contains("minValue changed from 1 to 5")),
                () -> assertTrue(detail.contains("maxValue changed from 1000 to 2000"))
        );
    }

    @Test
    @DisplayName("schema와 catalog 변경 시 'MODIFIED'로 감지되어야 함")
    void shouldDetectSchemaAndCatalogChanges() {
        SequenceModel oldSeq = createSequence("order_seq", 10, 100);
        oldSeq.setSchema("public");
        oldSeq.setCatalog("default");

        SequenceModel newSeq = createSequence("order_seq", 10, 100);
        newSeq.setSchema("audit");            // 변경
        newSeq.setCatalog("archive");         // 변경

        SchemaModel oldSchema = createSchema(oldSeq);
        SchemaModel newSchema = createSchema(newSeq);
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getSequenceDiffs().size());
        String detail = result.getSequenceDiffs().get(0).getChangeDetail();
        assertAll(
                () -> assertTrue(detail.contains("schema changed from public to audit")),
                () -> assertTrue(detail.contains("catalog changed from default to archive"))
        );
    }

    @Test
    @DisplayName("기존에 null이었던 schema나 catalog가 새로 지정되면 'MODIFIED'로 감지되어야 함")
    void shouldDetectNewlyAssignedSchemaOrCatalog() {
        SequenceModel oldSeq = createSequence("log_seq", 5, 10);
        // schema, catalog null

        SequenceModel newSeq = createSequence("log_seq", 5, 10);
        newSeq.setSchema("event");     // 새로 지정됨
        newSeq.setCatalog("main");     // 새로 지정됨

        SchemaModel oldSchema = createSchema(oldSeq);
        SchemaModel newSchema = createSchema(newSeq);
        DiffResult result = DiffResult.builder().build();

        sequenceDiffer.diff(oldSchema, newSchema, result);

        assertEquals(1, result.getSequenceDiffs().size());
        String detail = result.getSequenceDiffs().get(0).getChangeDetail();
        assertAll(
                () -> assertTrue(detail.contains("schema changed from null to event")),
                () -> assertTrue(detail.contains("catalog changed from null to main"))
        );
    }


    private SchemaModel createSchema(SequenceModel... sequences) {
        SchemaModel schema = new SchemaModel();
        if (sequences != null) {
            schema.setSequences(Arrays.stream(sequences)
                    .collect(Collectors.toMap(SequenceModel::getName, s -> s)));
        } else {
            schema.setSequences(Collections.emptyMap());
        }
        return schema;
    }

    private SequenceModel createSequence(String name, int initialValue, int allocationSize) {
        SequenceModel seq = SequenceModel.builder().build();
        seq.setName(name);
        seq.setInitialValue(initialValue);
        seq.setAllocationSize(allocationSize);
        return seq;
    }
}
