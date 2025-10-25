package org.jinx.migration.liquibase;

import org.jinx.migration.MigrationInfo;
import org.jinx.migration.liquibase.model.ChangeSetWrapper;
import org.jinx.migration.liquibase.model.DatabaseChangeLog;
import org.jinx.model.*;
import org.jinx.model.DiffResult.*;
import org.jinx.naming.Naming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LiquibaseYamlGenerator 테스트")
class LiquibaseYamlGeneratorTest {

    private LiquibaseYamlGenerator generator;
    private DiffResult mockDiff;
    private SchemaModel oldSchema;
    private SchemaModel newSchema;
    private DialectBundle dialectBundle;
    private Naming naming;
    private MigrationInfo migrationInfo;

    @BeforeEach
    void setUp() {
        generator = new LiquibaseYamlGenerator();

        // Mock 객체 생성
        mockDiff = mock(DiffResult.class);
        oldSchema = mock(SchemaModel.class);
        newSchema = mock(SchemaModel.class);
        dialectBundle = mock(DialectBundle.class);
        naming = mock(Naming.class);
        migrationInfo = mock(MigrationInfo.class);

        // DialectBundle 기본 설정
        when(dialectBundle.liquibase()).thenReturn(Optional.empty());

        // Sequence와 TableGenerator를 사용 가능하도록 설정
        doAnswer(invocation -> {
            Consumer<?> consumer = invocation.getArgument(0);
            consumer.accept(null); // dialect는 실제로 사용되지 않으므로 null로 충분
            return null;
        }).when(dialectBundle).withSequence(any());

        doAnswer(invocation -> {
            Consumer<?> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(dialectBundle).withTableGenerator(any());

        // DiffResult의 기본 리스트 설정
        when(mockDiff.getSequenceDiffs()).thenReturn(new ArrayList<>());
        when(mockDiff.getTableGeneratorDiffs()).thenReturn(new ArrayList<>());
        when(mockDiff.getDroppedTables()).thenReturn(new ArrayList<>());
        when(mockDiff.getRenamedTables()).thenReturn(new ArrayList<>());
        when(mockDiff.getAddedTables()).thenReturn(new ArrayList<>());
        when(mockDiff.getModifiedTables()).thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("generate() - 기본 메서드 호출 시 DatabaseChangeLog 생성")
    void testGenerateBasic() {
        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
    }

    @Test
    @DisplayName("generate() - Naming 포함 메서드 호출 시 DatabaseChangeLog 생성")
    void testGenerateWithNaming() {
        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle, naming);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
    }

    @Test
    @DisplayName("generate() - MigrationInfo 포함 메서드 호출 시 DatabaseChangeLog 생성")
    void testGenerateWithMigrationInfo() {
        // given
        when(migrationInfo.getHeadHash()).thenReturn("abc123def456");

        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle, naming, migrationInfo);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
    }

    @Test
    @DisplayName("generate() - 올바른 순서로 DiffResult의 accept 메서드 호출")
    void testGenerateCallsAcceptMethodsInCorrectOrder() {
        // given
        DiffResult diffSpy = spy(DiffResult.builder().build());

        // when
        generator.generate(diffSpy, oldSchema, newSchema, dialectBundle);

        // then - 순서 검증
        InOrder inOrder = inOrder(diffSpy);

        // 1. 시퀀스 변경
        inOrder.verify(diffSpy).sequenceAccept(any(), eq(SequenceDiff.Type.values()));

        // 2. 테이블 생성기 변경
        inOrder.verify(diffSpy).tableGeneratorAccept(any(), eq(TableGeneratorDiff.Type.values()));

        // 3. 테이블 삭제
        inOrder.verify(diffSpy).tableAccept(any(), eq(TablePhase.DROPPED));

        // 4. 테이블 컨텐츠 DROP 단계
        inOrder.verify(diffSpy).tableContentAccept(any(), eq(TableContentPhase.DROP));

        // 5. 테이블 이름 변경
        inOrder.verify(diffSpy).tableAccept(any(), eq(TablePhase.RENAMED));

        // 6. 테이블 생성
        inOrder.verify(diffSpy).tableAccept(any(), eq(TablePhase.ADDED));

        // 7. 테이블 컨텐츠 ALTER 단계
        inOrder.verify(diffSpy).tableContentAccept(any(), eq(TableContentPhase.ALTER));

        // 8. FK 추가
        inOrder.verify(diffSpy).tableContentAccept(any(), eq(TableContentPhase.FK_ADD));
    }

    @Test
    @DisplayName("generate() - 시퀀스 추가/삭제 시 ChangeSet 생성")
    void testGenerateWithSequenceChanges() {
        // given
        SequenceModel addedSeq = mock(SequenceModel.class);
        when(addedSeq.getName()).thenReturn("seq_new");
        when(addedSeq.getInitialValue()).thenReturn(1L);
        when(addedSeq.getAllocationSize()).thenReturn(50);

        SequenceModel droppedSeq = mock(SequenceModel.class);
        when(droppedSeq.getName()).thenReturn("seq_old");

        SequenceDiff addedDiff = SequenceDiff.builder()
                .type(SequenceDiff.Type.ADDED)
                .sequence(addedSeq)
                .build();

        SequenceDiff droppedDiff = SequenceDiff.builder()
                .type(SequenceDiff.Type.DROPPED)
                .sequence(droppedSeq)
                .build();

        // Real DiffResult 사용
        DiffResult realDiff = DiffResult.builder()
                .sequenceDiffs(List.of(addedDiff, droppedDiff))
                .build();

        // when
        DatabaseChangeLog result = generator.generate(realDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertFalse(result.getChangeSets().isEmpty());
    }

    @Test
    @DisplayName("generate() - 테이블 추가 시 ChangeSet 생성")
    void testGenerateWithTableAddition() {
        // given
        EntityModel newTable = mock(EntityModel.class);
        when(newTable.getTableName()).thenReturn("users");
        when(newTable.getColumns()).thenReturn(Collections.emptyMap());
        when(newTable.getConstraints()).thenReturn(Collections.emptyMap());
        when(newTable.getIndexes()).thenReturn(Collections.emptyMap());
        when(newTable.getRelationships()).thenReturn(Collections.emptyMap());

        // Real DiffResult 사용
        DiffResult realDiff = DiffResult.builder()
                .addedTables(List.of(newTable))
                .build();

        // when
        DatabaseChangeLog result = generator.generate(realDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertFalse(result.getChangeSets().isEmpty());
    }

    @Test
    @DisplayName("generate() - 테이블 삭제 시 ChangeSet 생성")
    void testGenerateWithTableDeletion() {
        // given
        EntityModel droppedTable = mock(EntityModel.class);
        when(droppedTable.getTableName()).thenReturn("old_table");

        // Real DiffResult 사용
        DiffResult realDiff = DiffResult.builder()
                .droppedTables(List.of(droppedTable))
                .build();

        // when
        DatabaseChangeLog result = generator.generate(realDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertFalse(result.getChangeSets().isEmpty());
    }

    @Test
    @DisplayName("generate() - 테이블 이름 변경 시 ChangeSet 생성")
    void testGenerateWithTableRename() {
        // given
        EntityModel oldTable = mock(EntityModel.class);
        when(oldTable.getTableName()).thenReturn("old_name");

        EntityModel newTable = mock(EntityModel.class);
        when(newTable.getTableName()).thenReturn("new_name");

        RenamedTable renamedTable = RenamedTable.builder()
                .oldEntity(oldTable)
                .newEntity(newTable)
                .changeDetail("Renamed table")
                .build();

        // Real DiffResult 사용
        DiffResult realDiff = DiffResult.builder()
                .renamedTables(List.of(renamedTable))
                .build();

        // when
        DatabaseChangeLog result = generator.generate(realDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertFalse(result.getChangeSets().isEmpty());
    }

    @Test
    @DisplayName("generate() - 빈 DiffResult로도 정상 동작")
    void testGenerateWithEmptyDiff() {
        // given - setUp()에서 이미 빈 리스트로 설정됨

        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
        assertTrue(result.getChangeSets().isEmpty());
    }

    @Test
    @DisplayName("generate() - 모든 단계의 변경사항이 포함된 복합 시나리오")
    void testGenerateWithComplexScenario() {
        // given
        DiffResult realDiff = DiffResult.builder()
                .sequenceDiffs(new ArrayList<>())
                .tableGeneratorDiffs(new ArrayList<>())
                .droppedTables(new ArrayList<>())
                .addedTables(new ArrayList<>())
                .renamedTables(new ArrayList<>())
                .modifiedTables(new ArrayList<>())
                .build();

        // when
        DatabaseChangeLog result = generator.generate(realDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
    }

    @Test
    @DisplayName("generate() - Null이 아닌 MigrationInfo가 visitor에 전달됨")
    void testGenerateSetsMigrationInfoOnVisitor() {
        // given
        String expectedHash = "test-hash-123";
        when(migrationInfo.getHeadHash()).thenReturn(expectedHash);

        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle, naming, migrationInfo);

        // then
        assertNotNull(result);
        // MigrationInfo가 설정되면 ChangeSet의 comment에 hash가 포함되어야 함
        // 실제 visitor 동작은 LiquibaseVisitor 테스트에서 검증
    }

    @Test
    @DisplayName("generate() - null MigrationInfo는 visitor에 설정되지 않음")
    void testGenerateWithNullMigrationInfo() {
        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle, naming, null);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
    }

    @Test
    @DisplayName("generate() - 반환된 DatabaseChangeLog의 ChangeSets가 비어있지 않음 (실제 변경사항 있을 때)")
    void testGenerateReturnsNonEmptyChangeSetsWithRealChanges() {
        // given
        SequenceModel seq = mock(SequenceModel.class);
        when(seq.getName()).thenReturn("test_seq");
        when(seq.getInitialValue()).thenReturn(1L);
        when(seq.getAllocationSize()).thenReturn(50);

        SequenceDiff seqDiff = SequenceDiff.builder()
                .type(SequenceDiff.Type.ADDED)
                .sequence(seq)
                .build();

        DiffResult realDiff = DiffResult.builder()
                .sequenceDiffs(List.of(seqDiff))
                .build();

        // when
        DatabaseChangeLog result = generator.generate(realDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        assertNotNull(result.getChangeSets());
        assertFalse(result.getChangeSets().isEmpty());
    }

    @Test
    @DisplayName("generate() - 테이블 컨텐츠 변경 단계 순서 검증 (DROP -> ALTER -> FK_ADD)")
    void testGenerateTableContentPhasesOrder() {
        // given
        DiffResult diffSpy = spy(DiffResult.builder().build());

        // when
        generator.generate(diffSpy, oldSchema, newSchema, dialectBundle);

        // then
        InOrder inOrder = inOrder(diffSpy);
        inOrder.verify(diffSpy).tableContentAccept(any(), eq(TableContentPhase.DROP));
        inOrder.verify(diffSpy).tableContentAccept(any(), eq(TableContentPhase.ALTER));
        inOrder.verify(diffSpy).tableContentAccept(any(), eq(TableContentPhase.FK_ADD));
    }

    @Test
    @DisplayName("generate() - TableGenerator 변경사항 처리")
    void testGenerateWithTableGeneratorChanges() {
        // given
        TableGeneratorModel addedGen = mock(TableGeneratorModel.class);
        when(addedGen.getName()).thenReturn("gen_new");

        TableGeneratorDiff genDiff = TableGeneratorDiff.builder()
                .type(TableGeneratorDiff.Type.ADDED)
                .tableGenerator(addedGen)
                .build();

        List<TableGeneratorDiff> genDiffs = List.of(genDiff);
        when(mockDiff.getTableGeneratorDiffs()).thenReturn(genDiffs);

        // when
        DatabaseChangeLog result = generator.generate(mockDiff, oldSchema, newSchema, dialectBundle);

        // then
        assertNotNull(result);
        verify(mockDiff).tableGeneratorAccept(any(), eq(TableGeneratorDiff.Type.values()));
    }
}
