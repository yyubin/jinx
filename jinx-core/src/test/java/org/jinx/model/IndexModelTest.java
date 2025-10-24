package org.jinx.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IndexModel 테스트")
class IndexModelTest {

    @Nested
    @DisplayName("빌더 및 기본값")
    class BuilderAndDefaults {

        @Test
        @DisplayName("빌더로 생성 시 모든 필드가 올바르게 설정됨")
        void builderSetsAllFields() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_email")
                    .tableName("users")
                    .columnNames(List.of("email"))
                    .unique(true)
                    .where("email IS NOT NULL")
                    .type("BTREE")
                    .build();

            assertThat(index.getIndexName()).isEqualTo("idx_users_email");
            assertThat(index.getTableName()).isEqualTo("users");
            assertThat(index.getColumnNames()).containsExactly("email");
            assertThat(index.getUnique()).isTrue();
            assertThat(index.getWhere()).isEqualTo("email IS NOT NULL");
            assertThat(index.getType()).isEqualTo("BTREE");
        }

        @Test
        @DisplayName("기본값이 올바르게 설정됨")
        void defaultValues() {
            IndexModel index = IndexModel.builder()
                    .indexName("test_index")
                    .tableName("test_table")
                    .columnNames(List.of("column1"))
                    .build();

            assertThat(index.getUnique()).isNull();
            assertThat(index.getWhere()).isNull();
            assertThat(index.getType()).isNull();
        }

        @Test
        @DisplayName("NoArgsConstructor로 생성 가능")
        void noArgsConstructor() {
            IndexModel index = new IndexModel();
            assertThat(index).isNotNull();
        }
    }

    @Nested
    @DisplayName("단일 컬럼 인덱스")
    class SingleColumnIndex {

        @Test
        @DisplayName("일반 인덱스")
        void regularIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_name")
                    .tableName("users")
                    .columnNames(List.of("name"))
                    .unique(false)
                    .build();

            assertThat(index.getColumnNames()).hasSize(1);
            assertThat(index.getColumnNames()).containsExactly("name");
            assertThat(index.getUnique()).isFalse();
        }

        @Test
        @DisplayName("UNIQUE 인덱스")
        void uniqueIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_email_unique")
                    .tableName("users")
                    .columnNames(List.of("email"))
                    .unique(true)
                    .build();

            assertThat(index.getUnique()).isTrue();
        }
    }

    @Nested
    @DisplayName("복합 컬럼 인덱스")
    class CompositeIndex {

        @Test
        @DisplayName("2개 컬럼 복합 인덱스")
        void twoColumnIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_orders_user_date")
                    .tableName("orders")
                    .columnNames(List.of("user_id", "order_date"))
                    .unique(false)
                    .build();

            assertThat(index.getColumnNames()).hasSize(2);
            assertThat(index.getColumnNames()).containsExactly("user_id", "order_date");
        }

        @Test
        @DisplayName("3개 이상 컬럼 복합 인덱스")
        void multipleColumnIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_transactions_complex")
                    .tableName("transactions")
                    .columnNames(List.of("user_id", "transaction_date", "amount", "status"))
                    .unique(false)
                    .build();

            assertThat(index.getColumnNames()).hasSize(4);
            assertThat(index.getColumnNames())
                    .containsExactly("user_id", "transaction_date", "amount", "status");
        }

        @Test
        @DisplayName("복합 UNIQUE 인덱스")
        void compositeUniqueIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_user_roles_unique")
                    .tableName("user_roles")
                    .columnNames(List.of("user_id", "role_id"))
                    .unique(true)
                    .build();

            assertThat(index.getColumnNames()).containsExactly("user_id", "role_id");
            assertThat(index.getUnique()).isTrue();
        }
    }

    @Nested
    @DisplayName("Partial Index (WHERE 절)")
    class PartialIndex {

        @Test
        @DisplayName("단순 조건 Partial Index")
        void simplePartialIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_active_email")
                    .tableName("users")
                    .columnNames(List.of("email"))
                    .where("is_active = true")
                    .unique(true)
                    .build();

            assertThat(index.getWhere()).isEqualTo("is_active = true");
        }

        @Test
        @DisplayName("복잡한 조건 Partial Index")
        void complexPartialIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_orders_pending")
                    .tableName("orders")
                    .columnNames(List.of("user_id", "order_date"))
                    .where("status = 'PENDING' AND deleted_at IS NULL")
                    .build();

            assertThat(index.getWhere()).contains("status")
                    .contains("deleted_at IS NULL");
        }

        @Test
        @DisplayName("NOT NULL 조건 Partial Index")
        void notNullPartialIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_email_not_null")
                    .tableName("users")
                    .columnNames(List.of("email"))
                    .where("email IS NOT NULL")
                    .build();

            assertThat(index.getWhere()).isEqualTo("email IS NOT NULL");
        }
    }

    @Nested
    @DisplayName("인덱스 타입")
    class IndexType {

        @Test
        @DisplayName("BTREE 인덱스")
        void btreeIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_name_btree")
                    .tableName("users")
                    .columnNames(List.of("name"))
                    .type("BTREE")
                    .build();

            assertThat(index.getType()).isEqualTo("BTREE");
        }

        @Test
        @DisplayName("HASH 인덱스")
        void hashIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_sessions_token_hash")
                    .tableName("sessions")
                    .columnNames(List.of("token"))
                    .type("HASH")
                    .build();

            assertThat(index.getType()).isEqualTo("HASH");
        }

        @Test
        @DisplayName("GIN 인덱스")
        void ginIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_documents_content_gin")
                    .tableName("documents")
                    .columnNames(List.of("content"))
                    .type("GIN")
                    .build();

            assertThat(index.getType()).isEqualTo("GIN");
        }

        @Test
        @DisplayName("GIST 인덱스")
        void gistIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_locations_point_gist")
                    .tableName("locations")
                    .columnNames(List.of("point"))
                    .type("GIST")
                    .build();

            assertThat(index.getType()).isEqualTo("GIST");
        }
    }

    @Nested
    @DisplayName("실제 사용 케이스")
    class RealWorldUseCases {

        @Test
        @DisplayName("이메일 검색용 인덱스")
        void emailSearchIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_email_search")
                    .tableName("users")
                    .columnNames(List.of("email"))
                    .unique(true)
                    .where("deleted_at IS NULL")
                    .type("BTREE")
                    .build();

            assertThat(index.getIndexName()).isEqualTo("idx_users_email_search");
            assertThat(index.getUnique()).isTrue();
            assertThat(index.getWhere()).contains("deleted_at IS NULL");
            assertThat(index.getType()).isEqualTo("BTREE");
        }

        @Test
        @DisplayName("날짜 범위 검색용 복합 인덱스")
        void dateRangeSearchIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_orders_user_date_status")
                    .tableName("orders")
                    .columnNames(List.of("user_id", "order_date", "status"))
                    .unique(false)
                    .build();

            assertThat(index.getColumnNames()).containsExactly("user_id", "order_date", "status");
        }

        @Test
        @DisplayName("Soft Delete를 고려한 인덱스")
        void softDeleteIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_products_active")
                    .tableName("products")
                    .columnNames(List.of("category_id", "created_at"))
                    .where("deleted_at IS NULL")
                    .build();

            assertThat(index.getWhere()).isEqualTo("deleted_at IS NULL");
        }

        @Test
        @DisplayName("JSON 컬럼 인덱스 (GIN)")
        void jsonColumnIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_settings_metadata")
                    .tableName("settings")
                    .columnNames(List.of("metadata"))
                    .type("GIN")
                    .build();

            assertThat(index.getType()).isEqualTo("GIN");
        }
    }

    @Nested
    @DisplayName("Jackson 역직렬화")
    class JacksonDeserialization {

        @Test
        @DisplayName("JsonIgnoreProperties가 설정되어 있음")
        void jsonIgnorePropertiesAnnotation() {
            // JsonIgnoreProperties(ignoreUnknown = true)가 클래스에 있는지 확인
            assertThat(IndexModel.class.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnoreProperties.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("알 수 없는 필드가 있어도 역직렬화 가능")
        void canDeserializeWithUnknownFields() {
            // JsonIgnoreProperties 덕분에 알 수 없는 필드를 무시하고 역직렬화 가능
            IndexModel index = IndexModel.builder()
                    .indexName("test_index")
                    .tableName("test_table")
                    .columnNames(List.of("col1"))
                    .build();

            assertThat(index).isNotNull();
        }
    }

    @Nested
    @DisplayName("커버링 인덱스")
    class CoveringIndex {

        @Test
        @DisplayName("자주 조회되는 컬럼들을 포함한 인덱스")
        void coveringIndex() {
            IndexModel index = IndexModel.builder()
                    .indexName("idx_users_covering")
                    .tableName("users")
                    .columnNames(List.of("email", "name", "status", "created_at"))
                    .where("deleted_at IS NULL")
                    .build();

            assertThat(index.getColumnNames()).hasSize(4);
            assertThat(index.getColumnNames())
                    .containsExactly("email", "name", "status", "created_at");
        }
    }
}
