package org.jinx.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConstraintModel 테스트")
class ConstraintModelTest {

    @Nested
    @DisplayName("빌더 및 기본값")
    class BuilderAndDefaults {

        @Test
        @DisplayName("빌더로 생성 시 모든 필드가 올바르게 설정됨")
        void builderSetsAllFields() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uq_users_email")
                    .schema("public")
                    .tableName("users")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("email"))
                    .build();

            assertThat(constraint.getName()).isEqualTo("uq_users_email");
            assertThat(constraint.getSchema()).isEqualTo("public");
            assertThat(constraint.getTableName()).isEqualTo("users");
            assertThat(constraint.getType()).isEqualTo(ConstraintType.UNIQUE);
            assertThat(constraint.getColumns()).containsExactly("email");
        }

        @Test
        @DisplayName("기본값이 올바르게 설정됨")
        void defaultValues() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("test_constraint")
                    .type(ConstraintType.UNIQUE)
                    .build();

            assertThat(constraint.getColumns()).isEmpty();
            assertThat(constraint.getReferencedColumns()).isEmpty();
            assertThat(constraint.getReferencedTable()).isNull();
            assertThat(constraint.getOnDelete()).isNull();
            assertThat(constraint.getOnUpdate()).isNull();
            assertThat(constraint.getCheckClause()).isNull();
            assertThat(constraint.getWhere()).isNull();
            assertThat(constraint.getOptions()).isNull();
        }

        @Test
        @DisplayName("NoArgsConstructor로 생성 가능")
        void noArgsConstructor() {
            ConstraintModel constraint = new ConstraintModel();
            assertThat(constraint).isNotNull();
            assertThat(constraint.getColumns()).isEmpty();
        }
    }

    @Nested
    @DisplayName("UNIQUE 제약 조건")
    class UniqueConstraint {

        @Test
        @DisplayName("단일 컬럼 UNIQUE 제약 조건")
        void singleColumnUnique() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uq_users_email")
                    .tableName("users")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("email"))
                    .build();

            assertThat(constraint.getType()).isEqualTo(ConstraintType.UNIQUE);
            assertThat(constraint.getColumns()).containsExactly("email");
        }

        @Test
        @DisplayName("복합 컬럼 UNIQUE 제약 조건")
        void multiColumnUnique() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uq_orders_user_date")
                    .tableName("orders")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("user_id", "order_date"))
                    .build();

            assertThat(constraint.getColumns()).containsExactly("user_id", "order_date");
        }

        @Test
        @DisplayName("Partial UNIQUE 제약 조건 (WHERE 절 포함)")
        void partialUnique() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uq_users_email_active")
                    .tableName("users")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("email"))
                    .where("is_active = true")
                    .build();

            assertThat(constraint.getWhere()).isEqualTo("is_active = true");
        }
    }

    @Nested
    @DisplayName("PRIMARY KEY 제약 조건")
    class PrimaryKeyConstraint {

        @Test
        @DisplayName("단일 컬럼 PRIMARY KEY")
        void singleColumnPrimaryKey() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("pk_users")
                    .tableName("users")
                    .type(ConstraintType.PRIMARY_KEY)
                    .columns(List.of("id"))
                    .build();

            assertThat(constraint.getType()).isEqualTo(ConstraintType.PRIMARY_KEY);
            assertThat(constraint.getColumns()).containsExactly("id");
        }

        @Test
        @DisplayName("복합 PRIMARY KEY")
        void compositePrimaryKey() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("pk_user_roles")
                    .tableName("user_roles")
                    .type(ConstraintType.PRIMARY_KEY)
                    .columns(List.of("user_id", "role_id"))
                    .build();

            assertThat(constraint.getColumns()).containsExactly("user_id", "role_id");
        }
    }

    @Nested
    @DisplayName("FOREIGN KEY 제약 조건")
    class ForeignKeyConstraint {

        @Test
        @DisplayName("단일 컬럼 FOREIGN KEY")
        void singleColumnForeignKey() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("fk_orders_user")
                    .tableName("orders")
                    .type(ConstraintType.INDEX)
                    .columns(List.of("user_id"))
                    .referencedTable("users")
                    .referencedColumns(List.of("id"))
                    .onDelete(OnDeleteAction.CASCADE)
                    .onUpdate(OnUpdateAction.RESTRICT)
                    .build();

            assertThat(constraint.getColumns()).containsExactly("user_id");
            assertThat(constraint.getReferencedTable()).isEqualTo("users");
            assertThat(constraint.getReferencedColumns()).containsExactly("id");
            assertThat(constraint.getOnDelete()).isEqualTo(OnDeleteAction.CASCADE);
            assertThat(constraint.getOnUpdate()).isEqualTo(OnUpdateAction.RESTRICT);
        }

        @Test
        @DisplayName("복합 FOREIGN KEY")
        void compositeForeignKey() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("fk_order_items_product")
                    .tableName("order_items")
                    .type(ConstraintType.INDEX)
                    .columns(List.of("product_id", "variant_id"))
                    .referencedTable("products")
                    .referencedColumns(List.of("id", "variant_id"))
                    .onDelete(OnDeleteAction.SET_NULL)
                    .build();

            assertThat(constraint.getColumns()).containsExactly("product_id", "variant_id");
            assertThat(constraint.getReferencedColumns()).containsExactly("id", "variant_id");
            assertThat(constraint.getOnDelete()).isEqualTo(OnDeleteAction.SET_NULL);
        }

        @Test
        @DisplayName("모든 OnDeleteAction 타입")
        void allOnDeleteActions() {
            ConstraintModel cascade = ConstraintModel.builder()
                    .name("fk_cascade")
                    .onDelete(OnDeleteAction.CASCADE)
                    .build();

            ConstraintModel setNull = ConstraintModel.builder()
                    .name("fk_set_null")
                    .onDelete(OnDeleteAction.SET_NULL)
                    .build();

            ConstraintModel restrict = ConstraintModel.builder()
                    .name("fk_restrict")
                    .onDelete(OnDeleteAction.RESTRICT)
                    .build();

            ConstraintModel noAction = ConstraintModel.builder()
                    .name("fk_no_action")
                    .onDelete(OnDeleteAction.NO_ACTION)
                    .build();

            assertThat(cascade.getOnDelete()).isEqualTo(OnDeleteAction.CASCADE);
            assertThat(setNull.getOnDelete()).isEqualTo(OnDeleteAction.SET_NULL);
            assertThat(restrict.getOnDelete()).isEqualTo(OnDeleteAction.RESTRICT);
            assertThat(noAction.getOnDelete()).isEqualTo(OnDeleteAction.NO_ACTION);
        }

        @Test
        @DisplayName("모든 OnUpdateAction 타입")
        void allOnUpdateActions() {
            ConstraintModel cascade = ConstraintModel.builder()
                    .name("fk_cascade")
                    .onUpdate(OnUpdateAction.CASCADE)
                    .build();

            ConstraintModel setNull = ConstraintModel.builder()
                    .name("fk_set_null")
                    .onUpdate(OnUpdateAction.SET_NULL)
                    .build();

            ConstraintModel restrict = ConstraintModel.builder()
                    .name("fk_restrict")
                    .onUpdate(OnUpdateAction.RESTRICT)
                    .build();

            ConstraintModel noAction = ConstraintModel.builder()
                    .name("fk_no_action")
                    .onUpdate(OnUpdateAction.NO_ACTION)
                    .build();

            assertThat(cascade.getOnUpdate()).isEqualTo(OnUpdateAction.CASCADE);
            assertThat(setNull.getOnUpdate()).isEqualTo(OnUpdateAction.SET_NULL);
            assertThat(restrict.getOnUpdate()).isEqualTo(OnUpdateAction.RESTRICT);
            assertThat(noAction.getOnUpdate()).isEqualTo(OnUpdateAction.NO_ACTION);
        }
    }

    @Nested
    @DisplayName("CHECK 제약 조건")
    class CheckConstraint {

        @Test
        @DisplayName("단순 CHECK 제약 조건")
        void simpleCheck() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("chk_products_price")
                    .tableName("products")
                    .type(ConstraintType.CHECK)
                    .checkClause("price > 0")
                    .build();

            assertThat(constraint.getType()).isEqualTo(ConstraintType.CHECK);
            assertThat(constraint.getCheckClause()).isEqualTo("price > 0");
        }

        @Test
        @DisplayName("복잡한 CHECK 제약 조건")
        void complexCheck() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("chk_orders_dates")
                    .tableName("orders")
                    .type(ConstraintType.CHECK)
                    .checkClause("order_date <= delivery_date AND delivery_date <= return_date")
                    .build();

            assertThat(constraint.getCheckClause())
                    .contains("order_date")
                    .contains("delivery_date")
                    .contains("return_date");
        }

        @Test
        @DisplayName("범위 CHECK 제약 조건")
        void rangeCheck() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("chk_users_age")
                    .tableName("users")
                    .type(ConstraintType.CHECK)
                    .checkClause("age >= 18 AND age <= 120")
                    .build();

            assertThat(constraint.getCheckClause()).isEqualTo("age >= 18 AND age <= 120");
        }
    }

    @Nested
    @DisplayName("INDEX 제약 조건")
    class IndexConstraint {

        @Test
        @DisplayName("INDEX 타입 제약 조건")
        void indexConstraint() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("idx_users_email")
                    .tableName("users")
                    .type(ConstraintType.INDEX)
                    .columns(List.of("email"))
                    .build();

            assertThat(constraint.getType()).isEqualTo(ConstraintType.INDEX);
        }
    }

    @Nested
    @DisplayName("스키마 지원")
    class SchemaSupport {

        @Test
        @DisplayName("스키마를 포함한 제약 조건")
        void constraintWithSchema() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uq_users_email")
                    .schema("public")
                    .tableName("users")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("email"))
                    .build();

            assertThat(constraint.getSchema()).isEqualTo("public");
        }
    }

    @Nested
    @DisplayName("옵션 필드")
    class OptionsField {

        @Test
        @DisplayName("추가 옵션을 설정할 수 있음")
        void constraintWithOptions() {
            ConstraintModel constraint = ConstraintModel.builder()
                    .name("idx_users_name")
                    .tableName("users")
                    .type(ConstraintType.INDEX)
                    .columns(List.of("name"))
                    .options("USING BTREE")
                    .build();

            assertThat(constraint.getOptions()).isEqualTo("USING BTREE");
        }
    }

    @Nested
    @DisplayName("모든 ConstraintType")
    class AllConstraintTypes {

        @Test
        @DisplayName("모든 제약 조건 타입을 생성할 수 있음")
        void allTypes() {
            ConstraintModel unique = ConstraintModel.builder()
                    .name("unique")
                    .type(ConstraintType.UNIQUE)
                    .build();

            ConstraintModel primaryKey = ConstraintModel.builder()
                    .name("pk")
                    .type(ConstraintType.PRIMARY_KEY)
                    .build();

            ConstraintModel check = ConstraintModel.builder()
                    .name("check")
                    .type(ConstraintType.CHECK)
                    .build();

            ConstraintModel index = ConstraintModel.builder()
                    .name("index")
                    .type(ConstraintType.INDEX)
                    .build();

            ConstraintModel notNull = ConstraintModel.builder()
                    .name("not_null")
                    .type(ConstraintType.NOT_NULL)
                    .build();

            ConstraintModel defaultVal = ConstraintModel.builder()
                    .name("default")
                    .type(ConstraintType.DEFAULT)
                    .build();

            ConstraintModel auto = ConstraintModel.builder()
                    .name("auto")
                    .type(ConstraintType.AUTO)
                    .build();

            assertThat(unique.getType()).isEqualTo(ConstraintType.UNIQUE);
            assertThat(primaryKey.getType()).isEqualTo(ConstraintType.PRIMARY_KEY);
            assertThat(check.getType()).isEqualTo(ConstraintType.CHECK);
            assertThat(index.getType()).isEqualTo(ConstraintType.INDEX);
            assertThat(notNull.getType()).isEqualTo(ConstraintType.NOT_NULL);
            assertThat(defaultVal.getType()).isEqualTo(ConstraintType.DEFAULT);
            assertThat(auto.getType()).isEqualTo(ConstraintType.AUTO);
        }
    }
}
