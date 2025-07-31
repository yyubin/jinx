package org.jinx.migration;

import org.jinx.migration.differs.SequenceDiffer;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.SequenceModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MigrationGeneratorTest {

    @Mock
    private Dialect dialect;
    @Mock
    private SchemaModel newSchema;

    private MigrationGenerator migrationGenerator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        migrationGenerator = new MigrationGenerator(dialect, newSchema);
    }

    private EntityModel createDummyEntity(String name) {
        return EntityModel.builder().tableName(name).build();
    }

    private SequenceModel createDummySequence(String name) {
        return SequenceModel.builder().name(name).build();
    }

    @Test
    @DisplayName("테이블 추가 Diff가 있을 때, CREATE TABLE SQL을 생성해야 한다")
    void generateSql_withAddedTable_shouldGenerateCreateTableSql() {
        // Given

        DiffResult diff = DiffResult.builder().build();
        EntityModel addedTable = createDummyEntity("new_users");
        diff.getAddedTables().add(addedTable);

        when(dialect.getCreateTableSql(addedTable)).thenReturn("CREATE TABLE new_users (...);");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("CREATE TABLE new_users (...);");
    }

    @Test
    @DisplayName("테이블 삭제 Diff가 있을 때, DROP TABLE SQL을 생성해야 한다")
    void generateSql_withDroppedTable_shouldGenerateDropTableSql() {
        // Given
        DiffResult diff = DiffResult.builder().build();
        EntityModel droppedTable = createDummyEntity("old_users");
        diff.getDroppedTables().add(droppedTable);

        when(dialect.getDropTableSql(droppedTable)).thenReturn("DROP TABLE old_users;");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("DROP TABLE old_users;");
    }

    @Test
    @DisplayName("테이블 수정 Diff가 있을 때, ALTER TABLE SQL을 생성해야 한다")
    void generateSql_withModifiedTable_shouldGenerateAlterTableSql() {
        // Given
        DiffResult diff = DiffResult.builder().build();
        DiffResult.ModifiedEntity modifiedEntity = DiffResult.ModifiedEntity.builder()
                .newEntity(createDummyEntity("users"))
                .build();
        diff.getModifiedTables().add(modifiedEntity);

        when(dialect.getAlterTableSql(modifiedEntity)).thenReturn("ALTER TABLE users ADD COLUMN email VARCHAR(255);");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("ALTER TABLE users ADD COLUMN email VARCHAR(255);");
    }

    @Test
    @DisplayName("시퀀스 추가 Diff가 있을 때, CREATE SEQUENCE SQL을 생성해야 한다")
    void generateSql_withAddedSequence_shouldGenerateCreateSequenceSql() {
        // Given
        DiffResult diff = DiffResult.builder().build();
        SequenceModel sequence = createDummySequence("user_seq");
        diff.getSequenceDiffs().add(DiffResult.SequenceDiff.added(sequence));

        when(dialect.getCreateSequenceSql(sequence)).thenReturn("CREATE SEQUENCE user_seq;");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("CREATE SEQUENCE user_seq;");
    }

    @Test
    @DisplayName("시퀀스 삭제 Diff가 있을 때, DROP SEQUENCE SQL을 생성해야 한다")
    void generateSql_withDroppedSequence_shouldGenerateDropSequenceSql() {
        // Given
        DiffResult diff = DiffResult.builder().build();
        SequenceModel sequence = createDummySequence("user_seq");
        diff.getSequenceDiffs().add(DiffResult.SequenceDiff.dropped(sequence));

        when(dialect.getDropSequenceSql(sequence)).thenReturn("DROP SEQUENCE user_seq;");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("DROP SEQUENCE user_seq;");
    }

    @Test
    @DisplayName("시퀀스 수정 Diff가 있을 때, ALTER SEQUENCE SQL을 생성해야 한다")
    void generateSql_withModifiedSequence_shouldGenerateAlterSequenceSql() {
        // Given
        DiffResult diff = DiffResult.builder().build();

        // Arrange: 이름은 같고 속성은 다른 두 시퀀스를 생성합니다.
        SequenceModel oldSequence = SequenceModel.builder().name("user_seq").initialValue(1).build();
        SequenceModel newSequence = SequenceModel.builder().name("user_seq").initialValue(100).build();

        DiffResult.SequenceDiff modifiedDiff = DiffResult.SequenceDiff.builder()
                .type(DiffResult.SequenceDiff.Type.MODIFIED)
                .oldSequence(oldSequence)
                .sequence(newSequence)
                .build();
        diff.getSequenceDiffs().add(modifiedDiff);

        when(dialect.getAlterSequenceSql(newSequence, oldSequence)).thenReturn("ALTER SEQUENCE user_seq START WITH 100;");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("ALTER SEQUENCE user_seq START WITH 100;");
    }

    @Test
    @DisplayName("경고(Warning)가 있을 때, SQL 주석으로 경고를 생성해야 한다")
    void generateSql_withWarnings_shouldGenerateSqlComments() {
        // Given
        DiffResult diff = DiffResult.builder().build();
        diff.getWarnings().add("Data loss might occur in users.email column.");

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).contains("-- WARNING: Data loss might occur in users.email column.");
    }

    @Test
    @DisplayName("여러 종류의 Diff가 있을 때, 올바른 순서로 모든 SQL을 생성해야 한다")
    void generateSql_withMultipleDiffs_shouldGenerateAllSqlInCorrectOrder() {
        // Given
        DiffResult diff = DiffResult.builder().build();

        // 1. Drop Table
        EntityModel droppedTable = createDummyEntity("old_products");
        diff.getDroppedTables().add(droppedTable);

        // 2. Add Table
        EntityModel addedTable = createDummyEntity("new_orders");
        diff.getAddedTables().add(addedTable);

        // 3. Modify Table
        DiffResult.ModifiedEntity modifiedEntity = DiffResult.ModifiedEntity.builder().newEntity(createDummyEntity("users")).build();
        diff.getModifiedTables().add(modifiedEntity);

        // 4. Add Sequence
        SequenceModel addedSequence = createDummySequence("order_seq");
        diff.getSequenceDiffs().add(DiffResult.SequenceDiff.added(addedSequence));

        // Mock dialect calls
        when(dialect.preSchemaObjects(any(SchemaModel.class))).thenReturn("-- pre-schema setup");
        when(dialect.getDropTableSql(droppedTable)).thenReturn("DROP TABLE old_products;");
        when(dialect.getCreateTableSql(addedTable)).thenReturn("CREATE TABLE new_orders (...);");
        when(dialect.getAlterTableSql(modifiedEntity)).thenReturn("ALTER TABLE users ...;");
        when(dialect.getCreateSequenceSql(addedSequence)).thenReturn("CREATE SEQUENCE order_seq;");

        // Expected order: pre-schema -> sequences -> drops -> adds -> alters
        String expectedSql = String.join("\n",
                "-- pre-schema setup",
                "CREATE SEQUENCE order_seq;",
                "DROP TABLE old_products;",
                "CREATE TABLE new_orders (...);",
                "ALTER TABLE users ...;"
        );

        // When
        String sql = migrationGenerator.generateSql(diff);

        // Then
        assertThat(sql).isEqualTo(expectedSql);
    }
}
