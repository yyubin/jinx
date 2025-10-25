package org.jinx.handler.builtins;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;
import org.jinx.model.SchemaModel;
import org.jinx.naming.Naming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TableAdapter 테스트")
class TableAdapterTest {

    private ProcessingContext ctx;
    private Naming mockNaming;

    @BeforeEach
    void setUp() {
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        Messager messager = mock(Messager.class);
        when(env.getMessager()).thenReturn(messager);

        ctx = new ProcessingContext(env, SchemaModel.builder().build());

        // Naming mock 설정
        mockNaming = mock(Naming.class);
        when(mockNaming.uqName(any(), anyList())).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            List<String> cols = invocation.getArgument(1);
            return "uq_" + table + "_" + String.join("_", cols);
        });
        when(mockNaming.ckName(any(String.class), any(CheckConstraint.class))).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            return "ck_" + table + "_check";
        });
        when(mockNaming.ixName(any(), anyList())).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            List<String> cols = invocation.getArgument(1);
            return "ix_" + table + "_" + String.join("_", cols);
        });

        // ProcessingContext의 naming을 mock으로 교체
        try {
            java.lang.reflect.Field namingField = ProcessingContext.class.getDeclaredField("naming");
            namingField.setAccessible(true);
            namingField.set(ctx, mockNaming);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("생성자로 Table과 ProcessingContext 저장")
    void constructorStoresTableAndContext() {
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getName()).isEqualTo("users");
    }

    @Test
    @DisplayName("getName() - Table의 name 반환")
    void getNameReturnsTableName() {
        Table table = createTable("products", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getName()).isEqualTo("products");
    }

    @Test
    @DisplayName("getSchema() - 값이 있을 때 Optional로 반환")
    void getSchemaReturnsOptionalWhenPresent() {
        Table table = createTable("users", "public", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        Optional<String> schema = adapter.getSchema();
        assertThat(schema).isPresent();
        assertThat(schema.get()).isEqualTo("public");
    }

    @Test
    @DisplayName("getSchema() - 빈 문자열일 때 empty 반환")
    void getSchemaReturnsEmptyForBlankString() {
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getSchema()).isEmpty();
    }

    @Test
    @DisplayName("getCatalog() - 값이 있을 때 Optional로 반환")
    void getCatalogReturnsOptionalWhenPresent() {
        Table table = createTable("users", "", "mydb", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        Optional<String> catalog = adapter.getCatalog();
        assertThat(catalog).isPresent();
        assertThat(catalog.get()).isEqualTo("mydb");
    }

    @Test
    @DisplayName("getCatalog() - 빈 문자열일 때 empty 반환")
    void getCatalogReturnsEmptyForBlankString() {
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getCatalog()).isEmpty();
    }

    @Test
    @DisplayName("getComment() - 값이 있을 때 Optional로 반환")
    void getCommentReturnsOptionalWhenPresent() {
        Table table = createTable("users", "", "", "User accounts",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        Optional<String> comment = adapter.getComment();
        assertThat(comment).isPresent();
        assertThat(comment.get()).isEqualTo("User accounts");
    }

    @Test
    @DisplayName("getComment() - 빈 문자열일 때 empty 반환")
    void getCommentReturnsEmptyForBlankString() {
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getComment()).isEmpty();
    }

    @Test
    @DisplayName("getConstraints() - UNIQUE 제약 조건의 이름이 blank일 때 자동 생성")
    void getConstraintsGeneratesUniqueNameWhenBlank() {
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"email", "username"});
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("uq_users_email_username");
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraints.get(0).getColumns()).containsExactly("email", "username").inOrder();
    }

    @Test
    @DisplayName("getConstraints() - UNIQUE 제약 조건의 이름이 명시되어 있을 때 사용")
    void getConstraintsUsesExplicitUniqueName() {
        UniqueConstraint uc = createUniqueConstraint("uq_custom_email", new String[]{"email"});
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("uq_custom_email");
    }

    @Test
    @DisplayName("getConstraints() - 여러 UNIQUE 제약 조건 처리")
    void getConstraintsHandlesMultipleUniqueConstraints() {
        UniqueConstraint uc1 = createUniqueConstraint("", new String[]{"email"});
        UniqueConstraint uc2 = createUniqueConstraint("", new String[]{"username"});
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc1, uc2}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);
    }

    @Test
    @DisplayName("getConstraints() - CHECK 제약 조건 처리")
    void getConstraintsHandlesCheckConstraints() {
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("ck_users_check");
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.CHECK);
        assertThat(constraints.get(0).getCheckClause()).isEqualTo("age >= 18");
    }

    @Test
    @DisplayName("getConstraints() - CHECK 제약 조건의 명시적 이름 사용")
    void getConstraintsUsesExplicitCheckName() {
        CheckConstraint cc = createCheckConstraint("ck_age_limit", "age >= 18", "");
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("ck_age_limit");
    }

    @Test
    @DisplayName("getConstraints() - CHECK 제약 조건의 options 처리")
    void getConstraintsHandlesCheckOptions() {
        CheckConstraint cc = createCheckConstraint("", "status IN ('active', 'inactive')", "DEFERRABLE");
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getOptions()).isEqualTo("DEFERRABLE");
    }

    @Test
    @DisplayName("getConstraints() - CHECK options가 빈 문자열일 때 그대로 저장")
    void getConstraintsStoresEmptyCheckOptions() {
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        // TableAdapter는 emptyToNull을 사용하지 않으므로 빈 문자열 그대로
        assertThat(constraints.get(0).getOptions()).isEqualTo("");
    }

    @Test
    @DisplayName("getConstraints() - UNIQUE와 CHECK 제약 조건 모두 처리")
    void getConstraintsHandlesBothUniqueAndCheck() {
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"email"});
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc}, new CheckConstraint[]{cc});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);

        // UNIQUE가 먼저, CHECK가 나중
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraints.get(1).getType()).isEqualTo(ConstraintType.CHECK);
    }

    @Test
    @DisplayName("getConstraints() - 제약 조건이 없을 때 빈 리스트 반환")
    void getConstraintsReturnsEmptyListWhenNoConstraints() {
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getConstraints()).isEmpty();
    }

    @Test
    @DisplayName("getConstraints() - 여러 CHECK 제약 조건 처리")
    void getConstraintsHandlesMultipleCheckConstraints() {
        CheckConstraint cc1 = createCheckConstraint("", "age >= 18", "");
        CheckConstraint cc2 = createCheckConstraint("", "balance >= 0", "");
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc1, cc2});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getCheckClause()).isEqualTo("age >= 18");
        assertThat(constraints.get(1).getCheckClause()).isEqualTo("balance >= 0");
    }

    @Test
    @DisplayName("getIndexes() - 단일 인덱스 처리")
    void getIndexesHandlesSingleIndex() {
        Index idx = createIndex("idx_email", "email");
        Table table = createTable("users", "", "", "",
            new Index[]{idx}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_email");
        assertThat(indexes.get(0).getColumnNames()).containsExactly("email");
    }

    @Test
    @DisplayName("getIndexes() - 여러 컬럼을 가진 인덱스 처리")
    void getIndexesHandlesMultiColumnIndex() {
        Index idx = createIndex("idx_name", "first_name, last_name");
        Table table = createTable("users", "", "", "",
            new Index[]{idx}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getColumnNames()).containsExactly("first_name", "last_name").inOrder();
    }

    @Test
    @DisplayName("getIndexes() - columnList split 시 콤마 뒤 공백 제거")
    void getIndexesTrimWhitespaceAfterComma() {
        Index idx = createIndex("idx_name", "first_name,  last_name,  birth_date");
        Table table = createTable("users", "", "", "",
            new Index[]{idx}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        // ,\\s* 정규표현식은 콤마 뒤의 공백만 제거함
        assertThat(indexes.get(0).getColumnNames()).containsExactly("first_name", "last_name", "birth_date").inOrder();
    }

    @Test
    @DisplayName("getIndexes() - 여러 인덱스 처리")
    void getIndexesHandlesMultipleIndexes() {
        Index idx1 = createIndex("idx_email", "email");
        Index idx2 = createIndex("idx_name", "first_name, last_name");
        Table table = createTable("users", "", "", "",
            new Index[]{idx1, idx2}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_email");
        assertThat(indexes.get(1).getIndexName()).isEqualTo("idx_name");
    }

    @Test
    @DisplayName("getIndexes() - 인덱스가 없을 때 빈 리스트 반환")
    void getIndexesReturnsEmptyListWhenNoIndexes() {
        Table table = createTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getIndexes()).isEmpty();
    }

    @Test
    @DisplayName("통합 테스트 - 모든 속성을 가진 Table")
    void integrationTestWithAllAttributes() {
        Index idx = createIndex("idx_email", "email");
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"username"});
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "NOT NULL");

        Table table = createTable("users", "public", "mydb", "User accounts table",
            new Index[]{idx}, new UniqueConstraint[]{uc}, new CheckConstraint[]{cc});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getName()).isEqualTo("users");
        assertThat(adapter.getSchema()).hasValue("public");
        assertThat(adapter.getCatalog()).hasValue("mydb");
        assertThat(adapter.getComment()).hasValue("User accounts table");

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_email");

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraints.get(1).getType()).isEqualTo(ConstraintType.CHECK);
        assertThat(constraints.get(1).getOptions()).isEqualTo("NOT NULL");
    }

    @Test
    @DisplayName("빈 name을 가진 Table 처리")
    void handlesEmptyTableName() {
        Table table = createTable("", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getName()).isEmpty();
    }

    @Test
    @DisplayName("null schema와 catalog 처리")
    void handlesNullSchemaAndCatalog() {
        Table table = mock(Table.class);
        when(table.name()).thenReturn("users");
        when(table.schema()).thenReturn(null);
        when(table.catalog()).thenReturn(null);
        when(table.comment()).thenReturn("");
        when(table.indexes()).thenReturn(new Index[]{});
        when(table.uniqueConstraints()).thenReturn(new UniqueConstraint[]{});
        when(table.check()).thenReturn(new CheckConstraint[]{});

        TableAdapter adapter = new TableAdapter(table, ctx);

        assertThat(adapter.getSchema()).isEmpty();
        assertThat(adapter.getCatalog()).isEmpty();
    }

    // --- 헬퍼 메서드 ---

    private Table createTable(String name, String schema, String catalog, String comment,
                              Index[] indexes, UniqueConstraint[] uniqueConstraints,
                              CheckConstraint[] checkConstraints) {
        Table table = mock(Table.class);
        when(table.name()).thenReturn(name);
        when(table.schema()).thenReturn(schema);
        when(table.catalog()).thenReturn(catalog);
        when(table.comment()).thenReturn(comment);
        when(table.indexes()).thenReturn(indexes);
        when(table.uniqueConstraints()).thenReturn(uniqueConstraints);
        when(table.check()).thenReturn(checkConstraints);
        return table;
    }

    private Index createIndex(String name, String columnList) {
        Index index = mock(Index.class);
        when(index.name()).thenReturn(name);
        when(index.columnList()).thenReturn(columnList);
        return index;
    }

    private UniqueConstraint createUniqueConstraint(String name, String[] columnNames) {
        UniqueConstraint constraint = mock(UniqueConstraint.class);
        when(constraint.name()).thenReturn(name);
        when(constraint.columnNames()).thenReturn(columnNames);
        return constraint;
    }

    private CheckConstraint createCheckConstraint(String name, String constraint, String options) {
        CheckConstraint cc = mock(CheckConstraint.class);
        when(cc.name()).thenReturn(name);
        when(cc.constraint()).thenReturn(constraint);
        when(cc.options()).thenReturn(options);
        return cc;
    }
}
