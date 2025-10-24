package org.jinx.model;

import jakarta.persistence.InheritanceType;
import org.jinx.model.EntityModel.TableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EntityModel 테스트")
class EntityModelTest {

    @Nested
    @DisplayName("빌더 및 기본값")
    class BuilderAndDefaults {

        @Test
        @DisplayName("빌더로 생성 시 모든 필드가 올바르게 설정됨")
        void builderSetsAllFields() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .schema("public")
                    .catalog("mydb")
                    .comment("사용자 테이블")
                    .fqcn("org.example.User")
                    .tableType(TableType.ENTITY)
                    .build();

            assertThat(entity.getEntityName()).isEqualTo("org.example.User");
            assertThat(entity.getTableName()).isEqualTo("users");
            assertThat(entity.getSchema()).isEqualTo("public");
            assertThat(entity.getCatalog()).isEqualTo("mydb");
            assertThat(entity.getComment()).isEqualTo("사용자 테이블");
            assertThat(entity.getFqcn()).isEqualTo("org.example.User");
            assertThat(entity.getTableType()).isEqualTo(TableType.ENTITY);
        }

        @Test
        @DisplayName("기본값이 올바르게 설정됨")
        void defaultValues() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Product")
                    .tableName("products")
                    .build();

            assertThat(entity.getSchema()).isNull();
            assertThat(entity.getCatalog()).isNull();
            assertThat(entity.getComment()).isNull();
            assertThat(entity.getInheritance()).isNull();
            assertThat(entity.getParentEntity()).isNull();
            assertThat(entity.getFqcn()).isNull();
            assertThat(entity.getTableType()).isEqualTo(TableType.ENTITY);
            assertThat(entity.getColumns()).isEmpty();
            assertThat(entity.getIndexes()).isEmpty();
            assertThat(entity.getConstraints()).isEmpty();
            assertThat(entity.getRelationships()).isEmpty();
            assertThat(entity.getSecondaryTables()).isEmpty();
            assertThat(entity.isValid()).isTrue();
            assertThat(entity.getDiscriminatorValue()).isNull();
        }

        @Test
        @DisplayName("NoArgsConstructor로 생성 가능")
        void noArgsConstructor() {
            EntityModel entity = new EntityModel();
            assertThat(entity).isNotNull();
            assertThat(entity.getColumns()).isEmpty();
            assertThat(entity.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("컬럼 관리")
    class ColumnManagement {

        @Test
        @DisplayName("컬럼을 추가할 수 있음")
        void addColumn() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .build();

            ColumnKey key = ColumnKey.of("users", "user_id");
            ColumnModel column = ColumnModel.builder()
                    .tableName("users")
                    .columnName("user_id")
                    .javaType("java.lang.Long")
                    .isPrimaryKey(true)
                    .build();

            entity.getColumns().put(key, column);

            assertThat(entity.getColumns()).hasSize(1);
            assertThat(entity.getColumns().get(key)).isEqualTo(column);
        }

        @Test
        @DisplayName("여러 컬럼을 관리할 수 있음")
        void manageMultipleColumns() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .build();

            ColumnModel idColumn = ColumnModel.builder()
                    .tableName("users")
                    .columnName("id")
                    .javaType("java.lang.Long")
                    .isPrimaryKey(true)
                    .build();

            ColumnModel nameColumn = ColumnModel.builder()
                    .tableName("users")
                    .columnName("name")
                    .javaType("java.lang.String")
                    .build();

            entity.getColumns().put(ColumnKey.of("users", "id"), idColumn);
            entity.getColumns().put(ColumnKey.of("users", "name"), nameColumn);

            assertThat(entity.getColumns()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("인덱스 관리")
    class IndexManagement {

        @Test
        @DisplayName("인덱스를 추가할 수 있음")
        void addIndex() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .build();

            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_email")
                    .tableName("users")
                    .columnNames(List.of("email"))
                    .unique(true)
                    .build();

            entity.getIndexes().put("idx_users_email", index);

            assertThat(entity.getIndexes()).hasSize(1);
            assertThat(entity.getIndexes().get("idx_users_email")).isEqualTo(index);
        }

        @Test
        @DisplayName("복합 인덱스를 추가할 수 있음")
        void addCompositeIndex() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Order")
                    .tableName("orders")
                    .build();

            IndexModel index = IndexModel.builder()
                    .indexName("idx_orders_user_date")
                    .tableName("orders")
                    .columnNames(List.of("user_id", "order_date"))
                    .unique(false)
                    .build();

            entity.getIndexes().put("idx_orders_user_date", index);

            assertThat(entity.getIndexes().get("idx_orders_user_date").getColumnNames())
                    .containsExactly("user_id", "order_date");
        }
    }

    @Nested
    @DisplayName("제약 조건 관리")
    class ConstraintManagement {

        @Test
        @DisplayName("UNIQUE 제약 조건을 추가할 수 있음")
        void addUniqueConstraint() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .build();

            ConstraintModel constraint = ConstraintModel.builder()
                    .name("uq_users_email")
                    .tableName("users")
                    .type(ConstraintType.UNIQUE)
                    .columns(List.of("email"))
                    .build();

            entity.getConstraints().put("uq_users_email", constraint);

            assertThat(entity.getConstraints()).hasSize(1);
            assertThat(entity.getConstraints().get("uq_users_email").getType())
                    .isEqualTo(ConstraintType.UNIQUE);
        }

        @Test
        @DisplayName("CHECK 제약 조건을 추가할 수 있음")
        void addCheckConstraint() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Product")
                    .tableName("products")
                    .build();

            ConstraintModel constraint = ConstraintModel.builder()
                    .name("chk_products_price")
                    .tableName("products")
                    .type(ConstraintType.CHECK)
                    .checkClause("price > 0")
                    .build();

            entity.getConstraints().put("chk_products_price", constraint);

            assertThat(entity.getConstraints().get("chk_products_price").getCheckClause())
                    .isEqualTo("price > 0");
        }
    }

    @Nested
    @DisplayName("관계 관리")
    class RelationshipManagement {

        @Test
        @DisplayName("관계를 추가할 수 있음")
        void addRelationship() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Order")
                    .tableName("orders")
                    .build();

            RelationshipModel relationship = RelationshipModel.builder()
                    .constraintName("fk_orders_user")
                    .tableName("orders")
                    .type(RelationshipType.MANY_TO_ONE)
                    .columns(List.of("user_id"))
                    .referencedTable("users")
                    .referencedColumns(List.of("id"))
                    .onDelete(OnDeleteAction.CASCADE)
                    .build();

            entity.getRelationships().put("fk_orders_user", relationship);

            assertThat(entity.getRelationships()).hasSize(1);
            assertThat(entity.getRelationships().get("fk_orders_user").getType())
                    .isEqualTo(RelationshipType.MANY_TO_ONE);
        }
    }

    @Nested
    @DisplayName("상속 전략")
    class InheritanceStrategy {

        @Test
        @DisplayName("SINGLE_TABLE 상속 전략이 올바르게 설정됨")
        void singleTableInheritance() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Animal")
                    .tableName("animals")
                    .inheritance(InheritanceType.SINGLE_TABLE)
                    .discriminatorValue("ANIMAL")
                    .build();

            assertThat(entity.getInheritance()).isEqualTo(InheritanceType.SINGLE_TABLE);
            assertThat(entity.getDiscriminatorValue()).isEqualTo("ANIMAL");
        }

        @Test
        @DisplayName("JOINED 상속 전략이 올바르게 설정됨")
        void joinedInheritance() {
            EntityModel parentEntity = EntityModel.builder()
                    .entityName("org.example.Vehicle")
                    .tableName("vehicles")
                    .inheritance(InheritanceType.JOINED)
                    .build();

            EntityModel childEntity = EntityModel.builder()
                    .entityName("org.example.Car")
                    .tableName("cars")
                    .inheritance(InheritanceType.JOINED)
                    .parentEntity("org.example.Vehicle")
                    .build();

            assertThat(parentEntity.getInheritance()).isEqualTo(InheritanceType.JOINED);
            assertThat(childEntity.getParentEntity()).isEqualTo("org.example.Vehicle");
        }

        @Test
        @DisplayName("TABLE_PER_CLASS 상속 전략이 올바르게 설정됨")
        void tablePerClassInheritance() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Payment")
                    .tableName("payments")
                    .inheritance(InheritanceType.TABLE_PER_CLASS)
                    .build();

            assertThat(entity.getInheritance()).isEqualTo(InheritanceType.TABLE_PER_CLASS);
        }
    }

    @Nested
    @DisplayName("Secondary Table")
    class SecondaryTableManagement {

        @Test
        @DisplayName("Secondary table을 추가할 수 있음")
        void addSecondaryTable() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .build();

            SecondaryTableModel secondaryTable = SecondaryTableModel.builder()
                    .name("user_details")
                    .schema("public")
                    .build();

            entity.getSecondaryTables().add(secondaryTable);

            assertThat(entity.getSecondaryTables()).hasSize(1);
            assertThat(entity.getSecondaryTables().get(0).getName()).isEqualTo("user_details");
        }
    }

    @Nested
    @DisplayName("TableType")
    class TableTypeTest {

        @Test
        @DisplayName("ENTITY 타입이 기본값임")
        void entityTypeIsDefault() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Product")
                    .tableName("products")
                    .build();

            assertThat(entity.getTableType()).isEqualTo(TableType.ENTITY);
        }

        @Test
        @DisplayName("JOIN_TABLE 타입을 설정할 수 있음")
        void joinTableType() {
            EntityModel entity = EntityModel.builder()
                    .entityName("user_roles")
                    .tableName("user_roles")
                    .tableType(TableType.JOIN_TABLE)
                    .build();

            assertThat(entity.getTableType()).isEqualTo(TableType.JOIN_TABLE);
        }

        @Test
        @DisplayName("COLLECTION_TABLE 타입을 설정할 수 있음")
        void collectionTableType() {
            EntityModel entity = EntityModel.builder()
                    .entityName("user_emails")
                    .tableName("user_emails")
                    .tableType(TableType.COLLECTION_TABLE)
                    .build();

            assertThat(entity.getTableType()).isEqualTo(TableType.COLLECTION_TABLE);
        }

        @Test
        @DisplayName("사용 가능한 모든 TableType")
        void allTableTypes() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.Entity")
                    .tableName("entity")
                    .tableType(TableType.ENTITY)
                    .build();

            EntityModel joinTable = EntityModel.builder()
                    .entityName("user_roles")
                    .tableName("user_roles")
                    .tableType(TableType.JOIN_TABLE)
                    .build();

            EntityModel collectionTable = EntityModel.builder()
                    .entityName("user_emails")
                    .tableName("user_emails")
                    .tableType(TableType.COLLECTION_TABLE)
                    .build();

            assertThat(entity.getTableType()).isEqualTo(TableType.ENTITY);
            assertThat(joinTable.getTableType()).isEqualTo(TableType.JOIN_TABLE);
            assertThat(collectionTable.getTableType()).isEqualTo(TableType.COLLECTION_TABLE);
        }
    }

    @Nested
    @DisplayName("유효성 검증")
    class Validation {

        @Test
        @DisplayName("기본적으로 유효함")
        void defaultValid() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.User")
                    .tableName("users")
                    .build();

            assertThat(entity.isValid()).isTrue();
        }

        @Test
        @DisplayName("유효하지 않음으로 설정 가능")
        void canBeInvalid() {
            EntityModel entity = EntityModel.builder()
                    .entityName("org.example.InvalidEntity")
                    .tableName("invalid")
                    .isValid(false)
                    .build();

            assertThat(entity.isValid()).isFalse();
        }
    }
}
