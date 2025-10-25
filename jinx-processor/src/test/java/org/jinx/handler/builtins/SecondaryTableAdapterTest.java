package org.jinx.handler.builtins;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Index;
import jakarta.persistence.SecondaryTable;
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

@DisplayName("SecondaryTableAdapter 테스트")
class SecondaryTableAdapterTest {

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
    @DisplayName("생성자로 SecondaryTable과 ProcessingContext 저장")
    void constructorStoresSecondaryTableAndContext() {
        SecondaryTable table = createSecondaryTable("user_profile", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getName()).isEqualTo("user_profile");
    }

    @Test
    @DisplayName("getName() - SecondaryTable의 name 반환")
    void getNameReturnsSecondaryTableName() {
        SecondaryTable table = createSecondaryTable("addresses", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getName()).isEqualTo("addresses");
    }

    @Test
    @DisplayName("getSchema() - 값이 있을 때 Optional로 반환")
    void getSchemaReturnsOptionalWhenPresent() {
        SecondaryTable table = createSecondaryTable("users", "public", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        Optional<String> schema = adapter.getSchema();
        assertThat(schema).isPresent();
        assertThat(schema.get()).isEqualTo("public");
    }

    @Test
    @DisplayName("getSchema() - 빈 문자열일 때 empty 반환")
    void getSchemaReturnsEmptyForBlankString() {
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getSchema()).isEmpty();
    }

    @Test
    @DisplayName("getCatalog() - 값이 있을 때 Optional로 반환")
    void getCatalogReturnsOptionalWhenPresent() {
        SecondaryTable table = createSecondaryTable("users", "", "mydb", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        Optional<String> catalog = adapter.getCatalog();
        assertThat(catalog).isPresent();
        assertThat(catalog.get()).isEqualTo("mydb");
    }

    @Test
    @DisplayName("getCatalog() - 빈 문자열일 때 empty 반환")
    void getCatalogReturnsEmptyForBlankString() {
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getCatalog()).isEmpty();
    }

    @Test
    @DisplayName("getComment() - 값이 있을 때 Optional로 반환")
    void getCommentReturnsOptionalWhenPresent() {
        SecondaryTable table = createSecondaryTable("users", "", "", "User profile data",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        Optional<String> comment = adapter.getComment();
        assertThat(comment).isPresent();
        assertThat(comment.get()).isEqualTo("User profile data");
    }

    @Test
    @DisplayName("getComment() - 빈 문자열일 때 empty 반환")
    void getCommentReturnsEmptyForBlankString() {
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getComment()).isEmpty();
    }

    @Test
    @DisplayName("getConstraints() - UNIQUE 제약 조건의 이름이 blank일 때 자동 생성")
    void getConstraintsGeneratesUniqueNameWhenBlank() {
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"email", "username"});
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("uq_users_email_username");
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraints.get(0).getColumns()).containsExactly("email", "username").inOrder();
    }

    @Test
    @DisplayName("getConstraints() - UNIQUE 제약 조건의 이름이 명시되어 있을 때 사용")
    void getConstraintsUsesExplicitUniqueName() {
        UniqueConstraint uc = createUniqueConstraint("uq_custom_name", new String[]{"email"});
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("uq_custom_name");
    }

    @Test
    @DisplayName("getConstraints() - 여러 UNIQUE 제약 조건 처리")
    void getConstraintsHandlesMultipleUniqueConstraints() {
        UniqueConstraint uc1 = createUniqueConstraint("", new String[]{"email"});
        UniqueConstraint uc2 = createUniqueConstraint("", new String[]{"username"});
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc1, uc2}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);
    }

    @Test
    @DisplayName("getConstraints() - CHECK 제약 조건 처리")
    void getConstraintsHandlesCheckConstraints() {
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("ck_users_check");
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.CHECK);
        assertThat(constraints.get(0).getCheckClause()).isEqualTo("age >= 18");
        assertThat(constraints.get(0).getOptions()).isNull();
    }

    @Test
    @DisplayName("getConstraints() - CHECK 제약 조건의 명시적 이름 사용")
    void getConstraintsUsesExplicitCheckName() {
        CheckConstraint cc = createCheckConstraint("ck_age_limit", "age >= 18", "");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getName()).isEqualTo("ck_age_limit");
    }

    @Test
    @DisplayName("getConstraints() - CHECK 제약 조건의 options 처리")
    void getConstraintsHandlesCheckOptions() {
        CheckConstraint cc = createCheckConstraint("", "status IN ('active', 'inactive')", "DEFERRABLE");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getOptions()).isEqualTo("DEFERRABLE");
    }

    @Test
    @DisplayName("getConstraints() - CHECK constraint가 null일 때 무시")
    void getConstraintsIgnoresNullCheckConstraint() {
        CheckConstraint cc = createCheckConstraint("", null, "");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).isEmpty();
    }

    @Test
    @DisplayName("getConstraints() - CHECK constraint가 blank일 때 무시")
    void getConstraintsIgnoresBlankCheckConstraint() {
        CheckConstraint cc = createCheckConstraint("", "   ", "");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).isEmpty();
    }

    @Test
    @DisplayName("getConstraints() - UNIQUE와 CHECK 제약 조건 모두 처리")
    void getConstraintsHandlesBothUniqueAndCheck() {
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"email"});
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{uc}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);

        // UNIQUE가 먼저, CHECK가 나중
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraints.get(1).getType()).isEqualTo(ConstraintType.CHECK);
    }

    @Test
    @DisplayName("getConstraints() - 제약 조건이 없을 때 빈 리스트 반환")
    void getConstraintsReturnsEmptyListWhenNoConstraints() {
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getConstraints()).isEmpty();
    }

    @Test
    @DisplayName("getIndexes() - 단일 인덱스 처리")
    void getIndexesHandlesSingleIndex() {
        Index idx = createIndex("idx_email", "email");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{idx}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_email");
        assertThat(indexes.get(0).getColumnNames()).containsExactly("email");
    }

    @Test
    @DisplayName("getIndexes() - 여러 컬럼을 가진 인덱스 처리")
    void getIndexesHandlesMultiColumnIndex() {
        Index idx = createIndex("idx_name", "first_name, last_name");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{idx}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getColumnNames()).containsExactly("first_name", "last_name").inOrder();
    }

    @Test
    @DisplayName("getIndexes() - columnList split 시 콤마 뒤 공백 제거")
    void getIndexesTrimWhitespaceAfterComma() {
        Index idx = createIndex("idx_name", "first_name,  last_name,  birth_date");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{idx}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        // ,\\s* 정규표현식은 콤마 뒤의 공백만 제거함
        assertThat(indexes.get(0).getColumnNames()).containsExactly("first_name", "last_name", "birth_date").inOrder();
    }

    @Test
    @DisplayName("getIndexes() - 여러 인덱스 처리")
    void getIndexesHandlesMultipleIndexes() {
        Index idx1 = createIndex("idx_email", "email");
        Index idx2 = createIndex("idx_name", "first_name, last_name");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{idx1, idx2}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_email");
        assertThat(indexes.get(1).getIndexName()).isEqualTo("idx_name");
    }

    @Test
    @DisplayName("getIndexes() - 인덱스가 없을 때 빈 리스트 반환")
    void getIndexesReturnsEmptyListWhenNoIndexes() {
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getIndexes()).isEmpty();
    }

    @Test
    @DisplayName("통합 테스트 - 모든 속성을 가진 SecondaryTable")
    void integrationTestWithAllAttributes() {
        Index idx = createIndex("idx_email", "email");
        UniqueConstraint uc = createUniqueConstraint("", new String[]{"username"});
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");

        SecondaryTable table = createSecondaryTable("user_profiles", "public", "mydb", "Extended user information",
            new Index[]{idx}, new UniqueConstraint[]{uc}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        assertThat(adapter.getName()).isEqualTo("user_profiles");
        assertThat(adapter.getSchema()).hasValue("public");
        assertThat(adapter.getCatalog()).hasValue("mydb");
        assertThat(adapter.getComment()).hasValue("Extended user information");

        List<IndexModel> indexes = adapter.getIndexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("idx_email");

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraints.get(1).getType()).isEqualTo(ConstraintType.CHECK);
    }

    @Test
    @DisplayName("CHECK 제약 조건 options가 빈 문자열일 때 null로 변환")
    void checkConstraintEmptyOptionsConvertedToNull() {
        CheckConstraint cc = createCheckConstraint("", "age >= 18", "");
        SecondaryTable table = createSecondaryTable("users", "", "", "",
            new Index[]{}, new UniqueConstraint[]{}, new CheckConstraint[]{cc});

        SecondaryTableAdapter adapter = new SecondaryTableAdapter(table, ctx);

        List<ConstraintModel> constraints = adapter.getConstraints();
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getOptions()).isNull();
    }

    // --- 헬퍼 메서드 ---

    private SecondaryTable createSecondaryTable(String name, String schema, String catalog, String comment,
                                                 Index[] indexes, UniqueConstraint[] uniqueConstraints,
                                                 CheckConstraint[] checkConstraints) {
        SecondaryTable table = mock(SecondaryTable.class);
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
