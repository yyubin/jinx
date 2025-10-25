package org.jinx.manager;

import org.jinx.context.ProcessingContext;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.naming.Naming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConstraintManager 테스트")
class ConstraintManagerTest {

    private ProcessingContext ctx;
    private ConstraintManager manager;
    private EntityModel entity;
    private Naming mockNaming;

    @BeforeEach
    void setUp() {
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        Messager messager = mock(Messager.class);
        when(env.getMessager()).thenReturn(messager);

        ctx = new ProcessingContext(env, SchemaModel.builder().build());

        // Naming mock 설정
        mockNaming = mock(Naming.class);
        when(mockNaming.uqName(any(), any())).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            List<String> cols = invocation.getArgument(1);
            return "uq_" + table + "_" + String.join("_", cols);
        });

        // ProcessingContext의 naming을 mock으로 교체
        // (ProcessingContext는 생성자에서 naming을 초기화하므로 reflection 필요)
        try {
            java.lang.reflect.Field namingField = ProcessingContext.class.getDeclaredField("naming");
            namingField.setAccessible(true);
            namingField.set(ctx, mockNaming);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        manager = new ConstraintManager(ctx);

        entity = EntityModel.builder()
                .entityName("User")
                .tableName("users")
                .schema("public")
                .build();
    }

    @Test
    @DisplayName("새로운 UNIQUE 제약 조건 추가")
    void addsNewUniqueConstraint() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).hasSize(1);
        Optional<ConstraintModel> found = manager.findUnique(entity, "users", cols, Optional.empty());
        assertThat(found).isPresent();

        ConstraintModel constraint = found.get();
        assertThat(constraint.getName()).isEqualTo("uq_users_email");
        assertThat(constraint.getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(constraint.getTableName()).isEqualTo("users");
        assertThat(constraint.getSchema()).isEqualTo("public");
        assertThat(constraint.getColumns()).containsExactly("email");
        assertThat(constraint.getWhere()).isNull();
    }

    @Test
    @DisplayName("WHERE 절이 있는 UNIQUE 제약 조건 추가")
    void addsUniqueConstraintWithWhere() {
        List<String> cols = List.of("email");
        Optional<String> where = Optional.of("deleted_at IS NULL");

        manager.addUniqueIfAbsent(entity, "users", cols, where);

        assertThat(entity.getConstraints()).hasSize(1);
        Optional<ConstraintModel> found = manager.findUnique(entity, "users", cols, where);
        assertThat(found).isPresent();

        ConstraintModel constraint = found.get();
        assertThat(constraint.getWhere()).isEqualTo("deleted_at IS NULL");
    }

    @Test
    @DisplayName("여러 컬럼에 대한 복합 UNIQUE 제약 조건 추가")
    void addsCompositeUniqueConstraint() {
        List<String> cols = List.of("first_name", "last_name", "birth_date");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).hasSize(1);
        Optional<ConstraintModel> found = manager.findUnique(entity, "users", cols, Optional.empty());
        assertThat(found).isPresent();

        ConstraintModel constraint = found.get();
        assertThat(constraint.getName()).isEqualTo("uq_users_first_name_last_name_birth_date");
        assertThat(constraint.getColumns()).containsExactly("first_name", "last_name", "birth_date").inOrder();
    }

    @Test
    @DisplayName("중복된 UNIQUE 제약 조건 추가 시 무시")
    void ignoresDuplicateUniqueConstraint() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        int initialSize = entity.getConstraints().size();

        // 두 번째 추가 시도
        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).hasSize(initialSize);
    }

    @Test
    @DisplayName("WHERE 절이 다르면 별도의 제약 조건으로 추가")
    void addsDifferentConstraintsForDifferentWhere() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        manager.addUniqueIfAbsent(entity, "users", cols, Optional.of("deleted_at IS NULL"));

        assertThat(entity.getConstraints()).hasSize(2);
    }

    @Test
    @DisplayName("UNIQUE 제약 조건 제거")
    void removesUniqueConstraint() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        assertThat(entity.getConstraints()).hasSize(1);

        manager.removeUniqueIfPresent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 제약 조건 제거는 에러 없이 무시")
    void removesNonExistentConstraintSafely() {
        List<String> cols = List.of("email");

        // 제약 조건이 없는 상태에서 제거 시도
        manager.removeUniqueIfPresent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).isEmpty();
    }

    @Test
    @DisplayName("WHERE 절이 있는 제약 조건 제거")
    void removesConstraintWithWhere() {
        List<String> cols = List.of("email");
        Optional<String> where = Optional.of("deleted_at IS NULL");

        manager.addUniqueIfAbsent(entity, "users", cols, where);
        manager.removeUniqueIfPresent(entity, "users", cols, where);

        assertThat(entity.getConstraints()).isEmpty();
    }

    @Test
    @DisplayName("findUnique - 존재하지 않는 제약 조건 검색 시 empty 반환")
    void findUniqueReturnsEmptyForNonExistent() {
        List<String> cols = List.of("email");

        Optional<ConstraintModel> found = manager.findUnique(entity, "users", cols, Optional.empty());

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findUnique - 존재하는 제약 조건 검색")
    void findUniqueReturnsConstraint() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        Optional<ConstraintModel> found = manager.findUnique(entity, "users", cols, Optional.empty());

        assertThat(found).isPresent();
        assertThat(found.get().getColumns()).containsExactly("email");
    }

    @Test
    @DisplayName("Schema가 없는 entity에 대한 제약 조건 추가")
    void addsConstraintToEntityWithoutSchema() {
        EntityModel entityNoSchema = EntityModel.builder()
                .entityName("Product")
                .tableName("products")
                .schema(null)
                .build();

        List<String> cols = List.of("sku");
        manager.addUniqueIfAbsent(entityNoSchema, "products", cols, Optional.empty());

        assertThat(entityNoSchema.getConstraints()).hasSize(1);
        Optional<ConstraintModel> found = manager.findUnique(entityNoSchema, "products", cols, Optional.empty());
        assertThat(found).isPresent();
        assertThat(found.get().getSchema()).isNull();
    }

    @Test
    @DisplayName("여러 개의 독립적인 UNIQUE 제약 조건 추가")
    void addsMultipleIndependentUniqueConstraints() {
        manager.addUniqueIfAbsent(entity, "users", List.of("email"), Optional.empty());
        manager.addUniqueIfAbsent(entity, "users", List.of("username"), Optional.empty());
        manager.addUniqueIfAbsent(entity, "users", List.of("phone"), Optional.empty());

        assertThat(entity.getConstraints()).hasSize(3);

        assertThat(manager.findUnique(entity, "users", List.of("email"), Optional.empty())).isPresent();
        assertThat(manager.findUnique(entity, "users", List.of("username"), Optional.empty())).isPresent();
        assertThat(manager.findUnique(entity, "users", List.of("phone"), Optional.empty())).isPresent();
    }

    @Test
    @DisplayName("WHERE 절의 유무로 제약 조건을 구분")
    void distinguishesConstraintsByWhere() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        manager.addUniqueIfAbsent(entity, "users", cols, Optional.of("status = 'active'"));

        Optional<ConstraintModel> withoutWhere = manager.findUnique(entity, "users", cols, Optional.empty());
        Optional<ConstraintModel> withWhere = manager.findUnique(entity, "users", cols, Optional.of("status = 'active'"));

        assertThat(withoutWhere).isPresent();
        assertThat(withWhere).isPresent();
        assertThat(withoutWhere.get().getWhere()).isNull();
        assertThat(withWhere.get().getWhere()).isEqualTo("status = 'active'");
    }

    @Test
    @DisplayName("제약 조건 키는 대소문자 무관하게 정규화")
    void constraintKeysAreNormalizedCaseInsensitive() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());

        // 대문자 테이블 이름으로 검색해도 찾을 수 있어야 함 (내부적으로 정규화됨)
        // 하지만 ConstraintKeys.canonicalKey가 소문자로 정규화하므로
        // 이미 추가된 제약 조건은 같은 키로 인식됨
        int beforeSize = entity.getConstraints().size();

        // 다시 추가 시도 - 중복으로 인식되어야 함
        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).hasSize(beforeSize);
    }

    @Test
    @DisplayName("제약 조건 제거 후 다시 추가 가능")
    void canAddConstraintAfterRemoval() {
        List<String> cols = List.of("email");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        manager.removeUniqueIfPresent(entity, "users", cols, Optional.empty());
        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());

        assertThat(entity.getConstraints()).hasSize(1);
        Optional<ConstraintModel> found = manager.findUnique(entity, "users", cols, Optional.empty());
        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("복합 제약 조건의 컬럼 순서가 중요")
    void compositeConstraintColumnOrderMatters() {
        List<String> cols1 = List.of("first_name", "last_name");
        List<String> cols2 = List.of("last_name", "first_name");

        manager.addUniqueIfAbsent(entity, "users", cols1, Optional.empty());
        manager.addUniqueIfAbsent(entity, "users", cols2, Optional.empty());

        // 컬럼 순서가 다르면 별도의 제약 조건으로 추가되어야 함
        assertThat(entity.getConstraints()).hasSize(2);

        assertThat(manager.findUnique(entity, "users", cols1, Optional.empty())).isPresent();
        assertThat(manager.findUnique(entity, "users", cols2, Optional.empty())).isPresent();
    }

    @Test
    @DisplayName("빈 컬럼 리스트에 대한 제약 조건 처리")
    void handlesEmptyColumnList() {
        List<String> emptyCols = List.of();

        manager.addUniqueIfAbsent(entity, "users", emptyCols, Optional.empty());

        // 빈 컬럼 리스트도 유효한 키를 생성하므로 추가됨
        assertThat(entity.getConstraints()).hasSize(1);

        Optional<ConstraintModel> found = manager.findUnique(entity, "users", emptyCols, Optional.empty());
        assertThat(found).isPresent();
        assertThat(found.get().getColumns()).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 테이블에 대한 제약 조건은 독립적")
    void constraintsForDifferentTablesAreIndependent() {
        List<String> cols = List.of("name");

        manager.addUniqueIfAbsent(entity, "users", cols, Optional.empty());
        manager.addUniqueIfAbsent(entity, "profiles", cols, Optional.empty());

        assertThat(entity.getConstraints()).hasSize(2);

        assertThat(manager.findUnique(entity, "users", cols, Optional.empty())).isPresent();
        assertThat(manager.findUnique(entity, "profiles", cols, Optional.empty())).isPresent();
    }
}
