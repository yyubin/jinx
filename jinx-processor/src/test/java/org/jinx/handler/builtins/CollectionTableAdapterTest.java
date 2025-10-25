package org.jinx.handler.builtins;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;
import org.jinx.naming.Naming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CollectionTableAdapter 테스트")
class CollectionTableAdapterTest {

    private ProcessingContext context;
    private Naming naming;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        naming = mock(Naming.class);
        when(context.getNaming()).thenReturn(naming);

        // 기본 naming 동작 설정
        when(naming.ixName(any(), any())).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            List<String> cols = invocation.getArgument(1);
            return "ix_" + table + "_" + String.join("_", cols);
        });

        when(naming.uqName(any(), any())).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            List<String> cols = invocation.getArgument(1);
            return "uq_" + table + "_" + String.join("_", cols);
        });
    }

    @Test
    @DisplayName("생성자 - effectiveTableName 없이")
    void constructor_WithoutEffectiveTableName() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{});

        // WHEN
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // THEN
        assertThat(adapter.getName()).isEqualTo("test_table");
    }

    @Test
    @DisplayName("생성자 - effectiveTableName 포함")
    void constructor_WithEffectiveTableName() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("", "", "", new Index[]{}, new UniqueConstraint[]{});

        // WHEN
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context, "effective_name");

        // THEN
        assertThat(adapter.getName()).isEqualTo("effective_name");
    }

    @Test
    @DisplayName("getName - 테이블 이름이 설정되어 있는 경우")
    void getName_WhenNameIsSet() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("my_collection", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        String name = adapter.getName();

        // THEN
        assertThat(name).isEqualTo("my_collection");
    }

    @Test
    @DisplayName("getName - 테이블 이름이 비어있고 effectiveTableName이 있는 경우")
    void getName_WhenNameIsEmptyButEffectiveTableNameExists() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context, "effective_table");

        // WHEN
        String name = adapter.getName();

        // THEN
        assertThat(name).isEqualTo("effective_table");
    }

    @Test
    @DisplayName("getName - 이름과 effectiveTableName 모두 없는 경우 폴백")
    void getName_WhenBothNameAndEffectiveTableNameAreEmpty() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        String name = adapter.getName();

        // THEN
        assertThat(name).isEqualTo("_anonymous_collection_");
    }

    @Test
    @DisplayName("getName - effectiveTableName이 빈 문자열인 경우 폴백")
    void getName_WhenEffectiveTableNameIsEmptyString() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context, "");

        // WHEN
        String name = adapter.getName();

        // THEN
        assertThat(name).isEqualTo("_anonymous_collection_");
    }

    @Test
    @DisplayName("getSchema - 스키마가 설정되어 있는 경우")
    void getSchema_WhenSchemaIsSet() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "public", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        Optional<String> schema = adapter.getSchema();

        // THEN
        assertThat(schema).isPresent();
        assertThat(schema.get()).isEqualTo("public");
    }

    @Test
    @DisplayName("getSchema - 스키마가 비어있는 경우")
    void getSchema_WhenSchemaIsEmpty() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        Optional<String> schema = adapter.getSchema();

        // THEN
        assertThat(schema).isEmpty();
    }

    @Test
    @DisplayName("getCatalog - 카탈로그가 설정되어 있는 경우")
    void getCatalog_WhenCatalogIsSet() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "mydb", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        Optional<String> catalog = adapter.getCatalog();

        // THEN
        assertThat(catalog).isPresent();
        assertThat(catalog.get()).isEqualTo("mydb");
    }

    @Test
    @DisplayName("getCatalog - 카탈로그가 비어있는 경우")
    void getCatalog_WhenCatalogIsEmpty() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        Optional<String> catalog = adapter.getCatalog();

        // THEN
        assertThat(catalog).isEmpty();
    }

    @Test
    @DisplayName("getIndexes - 인덱스가 없는 경우")
    void getIndexes_WhenNoIndexes() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<IndexModel> indexes = adapter.getIndexes();

        // THEN
        assertThat(indexes).isEmpty();
    }

    @Test
    @DisplayName("getIndexes - 인덱스 이름이 명시된 경우")
    void getIndexes_WithExplicitIndexName() {
        // GIVEN
        Index index = createIndex("idx_custom", "col1,col2", false);
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{index}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<IndexModel> indexes = adapter.getIndexes();

        // THEN
        assertThat(indexes).hasSize(1);
        IndexModel indexModel = indexes.get(0);
        assertThat(indexModel.getIndexName()).isEqualTo("idx_custom");
        assertThat(indexModel.getTableName()).isEqualTo("test_table");
        assertThat(indexModel.getColumnNames()).containsExactly("col1", "col2").inOrder();
    }

    @Test
    @DisplayName("getIndexes - 인덱스 이름이 없는 경우 자동 생성")
    void getIndexes_WithoutIndexName_ShouldGenerateName() {
        // GIVEN
        Index index = createIndex("", "email,created_at", false);
        CollectionTable collectionTable = createCollectionTable("users", "", "", new Index[]{index}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<IndexModel> indexes = adapter.getIndexes();

        // THEN
        assertThat(indexes).hasSize(1);
        IndexModel indexModel = indexes.get(0);
        assertThat(indexModel.getIndexName()).isEqualTo("ix_users_email_created_at");
        assertThat(indexModel.getColumnNames()).containsExactly("email", "created_at").inOrder();
    }

    @Test
    @DisplayName("getIndexes - 컬럼 리스트 트리밍 처리")
    void getIndexes_ShouldTrimColumnNames() {
        // GIVEN
        Index index = createIndex("", "  col1  ,  col2  ,  col3  ", false);
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{index}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<IndexModel> indexes = adapter.getIndexes();

        // THEN
        assertThat(indexes).hasSize(1);
        IndexModel indexModel = indexes.get(0);
        assertThat(indexModel.getColumnNames()).containsExactly("col1", "col2", "col3").inOrder();
    }

    @Test
    @DisplayName("getIndexes - 빈 컬럼 리스트는 건너뛰기")
    void getIndexes_ShouldSkipEmptyColumnList() {
        // GIVEN
        Index index1 = createIndex("idx_valid", "col1,col2", false);
        Index index2 = createIndex("idx_empty", "", false);
        Index index3 = createIndex("idx_whitespace", "   ,  ,  ", false);
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{index1, index2, index3}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<IndexModel> indexes = adapter.getIndexes();

        // THEN
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_valid");
    }

    @Test
    @DisplayName("getIndexes - 여러 개의 인덱스")
    void getIndexes_WithMultipleIndexes() {
        // GIVEN
        Index index1 = createIndex("idx1", "col1", false);
        Index index2 = createIndex("idx2", "col2,col3", false);
        Index index3 = createIndex("", "col4", false);
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{index1, index2, index3}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<IndexModel> indexes = adapter.getIndexes();

        // THEN
        assertThat(indexes).hasSize(3);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx1");
        assertThat(indexes.get(1).getIndexName()).isEqualTo("idx2");
        assertThat(indexes.get(2).getIndexName()).isEqualTo("ix_test_table_col4");
    }

    @Test
    @DisplayName("getConstraints - 제약 조건이 없는 경우")
    void getConstraints_WhenNoConstraints() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<ConstraintModel> constraints = adapter.getConstraints();

        // THEN
        assertThat(constraints).isEmpty();
    }

    @Test
    @DisplayName("getConstraints - UNIQUE 제약 조건 이름이 명시된 경우")
    void getConstraints_WithExplicitConstraintName() {
        // GIVEN
        UniqueConstraint uc = createUniqueConstraint("uq_custom", new String[]{"email", "username"});
        CollectionTable collectionTable = createCollectionTable("users", "", "", new Index[]{}, new UniqueConstraint[]{uc});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<ConstraintModel> constraints = adapter.getConstraints();

        // THEN
        assertThat(constraints).hasSize(1);
        ConstraintModel constraint = constraints.get(0);
        assertThat(constraint.getName()).isEqualTo("uq_custom");
        assertThat(constraint.getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraint.getTableName()).isEqualTo("users");
        assertThat(constraint.getColumns()).containsExactly("email", "username").inOrder();
    }

    @Test
    @DisplayName("getConstraints - UNIQUE 제약 조건 이름이 없는 경우 자동 생성")
    void getConstraints_WithoutConstraintName_ShouldGenerateName() {
        // GIVEN
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"col1", "col2"});
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{uc});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<ConstraintModel> constraints = adapter.getConstraints();

        // THEN
        assertThat(constraints).hasSize(1);
        ConstraintModel constraint = constraints.get(0);
        assertThat(constraint.getName()).isEqualTo("uq_test_table_col1_col2");
    }

    @Test
    @DisplayName("getConstraints - 컬럼명 트리밍 처리")
    void getConstraints_ShouldTrimColumnNames() {
        // GIVEN
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"  col1  ", "  col2  "});
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{uc});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<ConstraintModel> constraints = adapter.getConstraints();

        // THEN
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getColumns()).containsExactly("col1", "col2").inOrder();
    }

    @Test
    @DisplayName("getConstraints - 빈 컬럼 배열은 건너뛰기")
    void getConstraints_ShouldSkipEmptyColumns() {
        // GIVEN
        UniqueConstraint uc1 = createUniqueConstraint("uq1", new String[]{"col1", "col2"});
        UniqueConstraint uc2 = createUniqueConstraint("uq2", new String[]{});
        UniqueConstraint uc3 = createUniqueConstraint("uq3", new String[]{"  ", "  "});
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{uc1, uc2, uc3});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<ConstraintModel> constraints = adapter.getConstraints();

        // THEN
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("uq1");
    }

    @Test
    @DisplayName("getConstraints - 여러 개의 UNIQUE 제약 조건")
    void getConstraints_WithMultipleUniqueConstraints() {
        // GIVEN
        UniqueConstraint uc1 = createUniqueConstraint("uq1", new String[]{"email"});
        UniqueConstraint uc2 = createUniqueConstraint("uq2", new String[]{"username"});
        UniqueConstraint uc3 = createUniqueConstraint("", new String[]{"phone"});
        CollectionTable collectionTable = createCollectionTable("users", "", "", new Index[]{}, new UniqueConstraint[]{uc1, uc2, uc3});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        List<ConstraintModel> constraints = adapter.getConstraints();

        // THEN
        assertThat(constraints).hasSize(3);
        assertThat(constraints.get(0).getName()).isEqualTo("uq1");
        assertThat(constraints.get(1).getName()).isEqualTo("uq2");
        assertThat(constraints.get(2).getName()).isEqualTo("uq_users_phone");
    }

    @Test
    @DisplayName("getComment - 항상 empty 반환")
    void getComment_ShouldAlwaysReturnEmpty() {
        // GIVEN
        CollectionTable collectionTable = createCollectionTable("test_table", "", "", new Index[]{}, new UniqueConstraint[]{});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN
        Optional<String> comment = adapter.getComment();

        // THEN
        assertThat(comment).isEmpty();
    }

    @Test
    @DisplayName("통합 테스트 - 모든 속성 포함")
    void integrationTest_WithAllAttributes() {
        // GIVEN
        Index index = createIndex("idx_multi", "col1,col2", false);
        UniqueConstraint uc = createUniqueConstraint("uq_multi", new String[]{"col3", "col4"});
        CollectionTable collectionTable = createCollectionTable("full_table", "public", "mydb", new Index[]{index}, new UniqueConstraint[]{uc});
        CollectionTableAdapter adapter = new CollectionTableAdapter(collectionTable, context);

        // WHEN & THEN
        assertThat(adapter.getName()).isEqualTo("full_table");
        assertThat(adapter.getSchema()).hasValue("public");
        assertThat(adapter.getCatalog()).hasValue("mydb");
        assertThat(adapter.getIndexes()).hasSize(1);
        assertThat(adapter.getConstraints()).hasSize(1);
        assertThat(adapter.getComment()).isEmpty();
    }

    // --- 헬퍼 메서드 ---

    private CollectionTable createCollectionTable(String name, String schema, String catalog, Index[] indexes, UniqueConstraint[] uniqueConstraints) {
        CollectionTable table = mock(CollectionTable.class);
        when(table.name()).thenReturn(name);
        when(table.schema()).thenReturn(schema);
        when(table.catalog()).thenReturn(catalog);
        when(table.indexes()).thenReturn(indexes);
        when(table.uniqueConstraints()).thenReturn(uniqueConstraints);
        return table;
    }

    private Index createIndex(String name, String columnList, boolean unique) {
        Index index = mock(Index.class);
        when(index.name()).thenReturn(name);
        when(index.columnList()).thenReturn(columnList);
        when(index.unique()).thenReturn(unique);
        return index;
    }

    private UniqueConstraint createUniqueConstraint(String name, String[] columnNames) {
        UniqueConstraint constraint = mock(UniqueConstraint.class);
        when(constraint.name()).thenReturn(name);
        when(constraint.columnNames()).thenReturn(columnNames);
        return constraint;
    }
}
