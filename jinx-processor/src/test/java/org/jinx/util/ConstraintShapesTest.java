package org.jinx.util;

import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

@DisplayName("ConstraintShapes 테스트")
class ConstraintShapesTest {

    @Test
    @DisplayName("UNIQUE 제약 조건 - 컬럼이 정렬됨")
    void uniqueConstraintSortsColumns() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email", "username", "phone"))
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("username", "phone", "email"))
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // 컬럼 순서가 달라도 정렬되므로 같은 key
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("email,phone,username");
    }

    @Test
    @DisplayName("PRIMARY_KEY 제약 조건 - 컬럼이 정렬됨")
    void primaryKeyConstraintSortsColumns() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.PRIMARY_KEY)
                .tableName("orders")
                .columns(List.of("order_id", "user_id"))
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.PRIMARY_KEY)
                .tableName("orders")
                .columns(List.of("user_id", "order_id"))
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // 컬럼 순서가 달라도 정렬되므로 같은 key
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("order_id,user_id");
    }

    @Test
    @DisplayName("CHECK 제약 조건 - 컬럼 순서 유지")
    void checkConstraintPreservesColumnOrder() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.CHECK)
                .tableName("users")
                .columns(List.of("age", "status"))
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.CHECK)
                .tableName("users")
                .columns(List.of("status", "age"))
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // CHECK 제약 조건은 컬럼 순서가 유지되므로 다른 key
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).contains("age,status");
        assertThat(key2).contains("status,age");
    }

    @Test
    @DisplayName("UNIQUE 제약 조건 - WHERE 절이 있는 경우")
    void uniqueConstraintWithWhere() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("deleted_at IS NULL")
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("UNIQUE");
        assertThat(key).contains("users");
        assertThat(key).contains("email");
        assertThat(key).contains("deleted_at is null"); // 소문자로 정규화
    }

    @Test
    @DisplayName("UNIQUE 제약 조건 - WHERE 절이 없는 경우")
    void uniqueConstraintWithoutWhere() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where(null)
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("UNIQUE");
        assertThat(key).contains("users");
        assertThat(key).contains("email");
        assertThat(key).endsWith("_"); // where가 없으면 "_"
    }

    @Test
    @DisplayName("WHERE 절이 빈 문자열인 경우")
    void constraintWithEmptyStringWhere() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("")
                .build();

        String key = ConstraintShapes.shapeKey(c);

        // 빈 문자열은 null로 처리되므로 "_"
        assertThat(key).endsWith("_");
    }

    @Test
    @DisplayName("WHERE 절이 공백만 있는 경우")
    void constraintWithWhitespaceOnlyWhere() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("   ")
                .build();

        String key = ConstraintShapes.shapeKey(c);

        // 공백만 있는 문자열은 null로 처리되므로 "_"
        assertThat(key).endsWith("_");
    }

    @Test
    @DisplayName("인덱스 shape key 생성")
    void indexShapeKey() {
        IndexModel ix = IndexModel.builder()
                .tableName("users")
                .columnNames(List.of("email", "created_at"))
                .build();

        String key = ConstraintShapes.shapeKey(ix);

        assertThat(key).startsWith("IX|");
        assertThat(key).contains("users");
        assertThat(key).contains("email,created_at");
    }

    @Test
    @DisplayName("인덱스 - 컬럼 순서 유지")
    void indexPreservesColumnOrder() {
        IndexModel ix1 = IndexModel.builder()
                .tableName("users")
                .columnNames(List.of("email", "created_at"))
                .build();

        IndexModel ix2 = IndexModel.builder()
                .tableName("users")
                .columnNames(List.of("created_at", "email"))
                .build();

        String key1 = ConstraintShapes.shapeKey(ix1);
        String key2 = ConstraintShapes.shapeKey(ix2);

        // 인덱스는 컬럼 순서가 중요하므로 다른 key
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).contains("email,created_at");
        assertThat(key2).contains("created_at,email");
    }

    @Test
    @DisplayName("인덱스 - null 컬럼 리스트 처리")
    void indexWithNullColumnList() {
        IndexModel ix = IndexModel.builder()
                .tableName("users")
                .columnNames(null)
                .build();

        String key = ConstraintShapes.shapeKey(ix);

        assertThat(key).isEqualTo("IX|users|");
    }

    @Test
    @DisplayName("인덱스 - 빈 컬럼 리스트 처리")
    void indexWithEmptyColumnList() {
        IndexModel ix = IndexModel.builder()
                .tableName("users")
                .columnNames(List.of())
                .build();

        String key = ConstraintShapes.shapeKey(ix);

        assertThat(key).isEqualTo("IX|users|");
    }

    @Test
    @DisplayName("제약 조건 - null 컬럼 리스트 처리")
    void constraintWithNullColumnList() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(null)
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("UNIQUE|users||_");
    }

    @Test
    @DisplayName("제약 조건 - 빈 컬럼 리스트 처리")
    void constraintWithEmptyColumnList() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of())
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("UNIQUE|users||_");
    }

    @Test
    @DisplayName("대소문자 정규화 - 테이블명")
    void normalizesTableNameCase() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("Users")
                .columns(List.of("email"))
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("USERS")
                .columns(List.of("email"))
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // 대소문자 관계없이 같은 key
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("users");
    }

    @Test
    @DisplayName("대소문자 정규화 - 컬럼명")
    void normalizesColumnNameCase() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("Email", "UserName"))
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("EMAIL", "USERNAME"))
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // 대소문자 관계없이 같은 key
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("email,username");
    }

    @Test
    @DisplayName("대소문자 정규화 - WHERE 절")
    void normalizesWhereClauseCase() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("Deleted_At IS NULL")
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("DELETED_AT IS NULL")
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // 대소문자 관계없이 같은 key
        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("deleted_at is null");
    }

    @Test
    @DisplayName("공백 제거 - 테이블명")
    void trimsTableNameWhitespace() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("  users  ")
                .columns(List.of("email"))
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("users");
        assertThat(key).doesNotContain("  users  ");
    }

    @Test
    @DisplayName("공백 제거 - 컬럼명")
    void trimsColumnNameWhitespace() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("  email  ", "  username  "))
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("email,username");
        assertThat(key).doesNotContain("  email  ");
    }

    @Test
    @DisplayName("공백 제거 - WHERE 절")
    void trimsWhereClauseWhitespace() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("  deleted_at IS NULL  ")
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).contains("deleted_at is null");
        assertThat(key).doesNotContain("  deleted_at");
    }

    @Test
    @DisplayName("shape key 형식 검증 - UNIQUE")
    void uniqueShapeKeyFormat() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .where("status = 'active'")
                .build();

        String key = ConstraintShapes.shapeKey(c);

        // 형식: type|table|columns|where
        String[] parts = key.split("\\|");
        assertThat(parts).hasLength(4);
        assertThat(parts[0]).isEqualTo("UNIQUE");
        assertThat(parts[1]).isEqualTo("users");
        assertThat(parts[2]).isEqualTo("email");
        assertThat(parts[3]).isEqualTo("status = 'active'");
    }

    @Test
    @DisplayName("shape key 형식 검증 - 인덱스")
    void indexShapeKeyFormat() {
        IndexModel ix = IndexModel.builder()
                .tableName("users")
                .columnNames(List.of("email", "created_at"))
                .build();

        String key = ConstraintShapes.shapeKey(ix);

        // 형식: IX|table|columns
        String[] parts = key.split("\\|");
        assertThat(parts).hasLength(3);
        assertThat(parts[0]).isEqualTo("IX");
        assertThat(parts[1]).isEqualTo("users");
        assertThat(parts[2]).isEqualTo("email,created_at");
    }

    @Test
    @DisplayName("단일 컬럼 UNIQUE 제약 조건")
    void singleColumnUniqueConstraint() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("email"))
                .build();

        String key = ConstraintShapes.shapeKey(c);

        assertThat(key).isEqualTo("UNIQUE|users|email|_");
    }

    @Test
    @DisplayName("복합 컬럼 UNIQUE 제약 조건 - 정렬 확인")
    void multiColumnUniqueConstraintSorted() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName("users")
                .columns(List.of("z_col", "a_col", "m_col"))
                .build();

        String key = ConstraintShapes.shapeKey(c);

        // 알파벳 순서로 정렬되어야 함
        assertThat(key).contains("a_col,m_col,z_col");
    }

    @Test
    @DisplayName("NOT_NULL 제약 조건 - 컬럼 순서 유지")
    void notNullConstraintPreservesOrder() {
        ConstraintModel c1 = ConstraintModel.builder()
                .type(ConstraintType.NOT_NULL)
                .tableName("users")
                .columns(List.of("email", "username"))
                .build();

        ConstraintModel c2 = ConstraintModel.builder()
                .type(ConstraintType.NOT_NULL)
                .tableName("users")
                .columns(List.of("username", "email"))
                .build();

        String key1 = ConstraintShapes.shapeKey(c1);
        String key2 = ConstraintShapes.shapeKey(c2);

        // NOT_NULL은 UNIQUE/PRIMARY_KEY가 아니므로 순서 유지
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("인덱스 - 대소문자 정규화")
    void indexNormalizesCase() {
        IndexModel ix1 = IndexModel.builder()
                .tableName("Users")
                .columnNames(List.of("Email", "CreatedAt"))
                .build();

        IndexModel ix2 = IndexModel.builder()
                .tableName("USERS")
                .columnNames(List.of("EMAIL", "CREATEDAT"))
                .build();

        String key1 = ConstraintShapes.shapeKey(ix1);
        String key2 = ConstraintShapes.shapeKey(ix2);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("users");
        assertThat(key1).contains("email,createdat");
    }

    @Test
    @DisplayName("null 테이블명 처리")
    void handlesNullTableName() {
        ConstraintModel c = ConstraintModel.builder()
                .type(ConstraintType.UNIQUE)
                .tableName(null)
                .columns(List.of("email"))
                .build();

        String key = ConstraintShapes.shapeKey(c);

        // null은 빈 문자열로 정규화
        assertThat(key).startsWith("UNIQUE||");
    }
}
