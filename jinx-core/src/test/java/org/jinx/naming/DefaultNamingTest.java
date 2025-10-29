package org.jinx.naming;

import jakarta.persistence.CheckConstraint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class DefaultNamingTest {

    private DefaultNaming naming;

    @BeforeEach
    void setUp() {
        naming = new DefaultNaming(128);
    }

    @Nested
    @DisplayName("Foreign Key Column Name")
    class ForeignKeyColumnNameTests {

        @Test
        @DisplayName("기본 형식: owner_referencedPk")
        void basicFormat() {
            String result = naming.foreignKeyColumnName("Order", "CustomerID");
            assertEquals("order_customerid", result);
        }

        @Test
        @DisplayName("특수문자 정규화")
        void specialCharacterNormalization() {
            String result = naming.foreignKeyColumnName("Ord er!", "Custo-mer ID");
            assertEquals("ord_er__custo_mer_id", result);
        }

        @Test
        @DisplayName("대소문자 소문자로 변환")
        void lowercaseConversion() {
            String result = naming.foreignKeyColumnName("USER", "PROFILE_ID");
            assertEquals("user_profile_id", result);
        }

        @Test
        @DisplayName("null 처리")
        void nullHandling() {
            String result = naming.foreignKeyColumnName(null, null);
            assertEquals("null_null", result);
        }

        @Test
        @DisplayName("빈 문자열 처리")
        void emptyStringHandling() {
            String result = naming.foreignKeyColumnName("", "");
            assertEquals("x_x", result);
        }

        @Test
        @DisplayName("숫자 포함")
        void withNumbers() {
            String result = naming.foreignKeyColumnName("Order123", "Product456");
            assertEquals("order123_product456", result);
        }
    }

    @Nested
    @DisplayName("Join Table Name")
    class JoinTableNameTests {

        @Test
        @DisplayName("기본 형식: jt_a__b (정렬됨)")
        void basicFormatSorted() {
            String result = naming.joinTableName("Author", "Book");
            assertEquals("jt_author__book", result);
        }

        @Test
        @DisplayName("입력 순서 무관 (정렬 보장)")
        void orderIndependent() {
            String ab = naming.joinTableName("Author", "Book");
            String ba = naming.joinTableName("Book", "Author");
            assertEquals(ab, ba);
            assertEquals("jt_author__book", ab);
        }

        @Test
        @DisplayName("대소문자 정규화")
        void caseNormalization() {
            String result = naming.joinTableName("AUTHOR", "book");
            assertEquals("jt_author__book", result);
        }

        @Test
        @DisplayName("특수문자 정규화")
        void specialCharacters() {
            String result = naming.joinTableName("User-Profile", "Role!Group");
            assertEquals("jt_role_group__user_profile", result);
        }

        @Test
        @DisplayName("동일한 테이블명")
        void sameTableNames() {
            String result = naming.joinTableName("Users", "Users");
            assertEquals("jt_users__users", result);
        }
    }

    @Nested
    @DisplayName("Primary Key Name")
    class PrimaryKeyNameTests {

        @Test
        @DisplayName("기본 형식: pk_table__cols")
        void basicFormat() {
            String result = naming.pkName("Products", List.of("Id"));
            assertEquals("pk_products__id", result);
        }

        @Test
        @DisplayName("복합 PK - 정렬됨")
        void compositeKeySorted() {
            String result1 = naming.pkName("Products", List.of("Id", "Sku"));
            String result2 = naming.pkName("Products", List.of("Sku", "Id"));

            assertEquals("pk_products__id_sku", result1);
            assertEquals(result1, result2); // 순서 무관
        }

        @Test
        @DisplayName("대소문자 혼합")
        void mixedCase() {
            String result = naming.pkName("UserAccounts", List.of("UserId", "TenantId"));
            assertEquals("pk_useraccounts__tenantid_userid", result);
        }

        @Test
        @DisplayName("빈 컬럼 리스트")
        void emptyColumns() {
            String result = naming.pkName("Products", Collections.emptyList());
            assertEquals("pk_products__", result);
        }

        @Test
        @DisplayName("null 컬럼 리스트")
        void nullColumns() {
            String result = naming.pkName("Products", null);
            assertEquals("pk_products__", result);
        }
    }

    @Nested
    @DisplayName("Unique Constraint Name")
    class UniqueConstraintNameTests {

        @Test
        @DisplayName("기본 형식: uq_table__cols")
        void basicFormat() {
            String result = naming.uqName("Users", List.of("Email"));
            assertEquals("uq_users__email", result);
        }

        @Test
        @DisplayName("복합 유니크 제약조건")
        void compositeUnique() {
            String result = naming.uqName("Users", List.of("Email", "TenantId"));
            assertEquals("uq_users__email_tenantid", result);
        }

        @Test
        @DisplayName("정렬 보장")
        void sortingGuaranteed() {
            String result1 = naming.uqName("T", List.of("c", "b", "a"));
            String result2 = naming.uqName("T", List.of("a", "b", "c"));
            assertEquals(result1, result2);
        }
    }

    @Nested
    @DisplayName("Index Name")
    class IndexNameTests {

        @Test
        @DisplayName("기본 형식: ix_table__cols")
        void basicFormat() {
            String result = naming.ixName("Orders", List.of("CustomerId"));
            assertEquals("ix_orders__customerid", result);
        }

        @Test
        @DisplayName("복합 인덱스")
        void compositeIndex() {
            String result = naming.ixName("Orders", List.of("CustomerId", "OrderDate"));
            assertEquals("ix_orders__customerid_orderdate", result);
        }

        @Test
        @DisplayName("다양한 컬럼명")
        void variousColumnNames() {
            String result = naming.ixName("T", List.of("A", "b", "c2"));
            assertEquals("ix_t__a_b_c2", result);
        }
    }

    @Nested
    @DisplayName("Check Constraint Name")
    class CheckConstraintNameTests {

        @Test
        @DisplayName("컬럼 기반: ck_table__cols")
        void columnBased() {
            String result = naming.ckName("Orders", List.of("Qty", "Price"));
            assertEquals("ck_orders__price_qty", result);
        }

        @Test
        @DisplayName("정렬 보장")
        void sortingGuaranteed() {
            String result1 = naming.ckName("Orders", List.of("Qty", "Price"));
            String result2 = naming.ckName("Orders", List.of("Price", "Qty"));
            assertEquals(result1, result2);
        }

        @Test
        @DisplayName("CheckConstraint 어노테이션 기반")
        void annotationBased() {
            CheckConstraint cc = createCheckConstraint("price > 0 AND qty >= 1");
            String result = naming.ckName("Orders", cc);
            assertEquals("ck_orders__price_0_and_qty_1", result);
        }

        @Test
        @DisplayName("null CheckConstraint 처리")
        void nullCheckConstraint() {
            String result = naming.ckName("Orders", (CheckConstraint) null);
            // null constraint는 빈 문자열로 처리되어 'x'로 변환됨
            assertEquals("ck_orders__x", result);
        }

        @Test
        @DisplayName("복잡한 체크 제약조건 표현식")
        void complexCheckExpression() {
            CheckConstraint cc = createCheckConstraint("status IN ('ACTIVE', 'INACTIVE')");
            String result = naming.ckName("Users", cc);
            // 특수문자는 '_'로 치환되고, 연속 '_'는 하나로 병합됨
            // "status IN ('ACTIVE', 'INACTIVE')" -> "status_in_active_inactive"
            assertTrue(result.startsWith("ck_users__status"));
            assertTrue(result.contains("active"));
            assertTrue(result.contains("inactive"));
        }
    }

    @Nested
    @DisplayName("Not Null Constraint Name")
    class NotNullConstraintNameTests {

        @Test
        @DisplayName("기본 형식: nn_table__cols")
        void basicFormat() {
            String result = naming.nnName("Users", List.of("Email"));
            assertEquals("nn_users__email", result);
        }

        @Test
        @DisplayName("복수 컬럼")
        void multipleColumns() {
            String result = naming.nnName("T", List.of("A", "b", "c2"));
            assertEquals("nn_t__a_b_c2", result);
        }
    }

    @Nested
    @DisplayName("Default Constraint Name")
    class DefaultConstraintNameTests {

        @Test
        @DisplayName("기본 형식: df_table__cols")
        void basicFormat() {
            String result = naming.dfName("Users", List.of("Status"));
            assertEquals("df_users__status", result);
        }

        @Test
        @DisplayName("복수 컬럼")
        void multipleColumns() {
            String result = naming.dfName("T", List.of("A", "b", "c2"));
            assertEquals("df_t__a_b_c2", result);
        }
    }

    @Nested
    @DisplayName("Auto Constraint Name")
    class AutoConstraintNameTests {

        @Test
        @DisplayName("기본 형식: cn_table__cols")
        void basicFormat() {
            String result = naming.autoName("Users", List.of("Id"));
            assertEquals("cn_users__id", result);
        }

        @Test
        @DisplayName("복수 컬럼")
        void multipleColumns() {
            String result = naming.autoName("T", List.of("A", "b", "c2"));
            assertEquals("cn_t__a_b_c2", result);
        }
    }

    @Nested
    @DisplayName("Foreign Key Name")
    class ForeignKeyNameTests {

        @Test
        @DisplayName("기본 형식: fk_child__childCols__parent")
        void basicFormat() {
            String result = naming.fkName("OrderItems", List.of("ProductId"), "Products", List.of("Id"));
            assertEquals("fk_orderitems__productid__products", result);
        }

        @Test
        @DisplayName("복합 외래키")
        void compositeForeignKey() {
            String result = naming.fkName("Order Items", List.of("Product ID", "order_id"),
                                         "Products", List.of("ID"));
            assertEquals("fk_order_items__order_id_product_id__products", result);
        }

        @Test
        @DisplayName("정렬 보장")
        void sortingGuaranteed() {
            String result1 = naming.fkName("child", List.of("b", "a"), "parent", List.of("id"));
            String result2 = naming.fkName("child", List.of("a", "b"), "parent", List.of("id"));
            assertEquals(result1, result2);
        }

        @Test
        @DisplayName("parentCols는 이름에 미포함")
        void parentColsNotInName() {
            String result = naming.fkName("child", List.of("col1"), "parent", List.of("pk1", "pk2"));
            assertEquals("fk_child__col1__parent", result);
        }
    }

    @Nested
    @DisplayName("정규화 규칙 (norm)")
    class NormalizationRulesTests {

        @Test
        @DisplayName("null은 'null'로 변환")
        void nullToString() {
            String result = naming.pkName(null, List.of("id"));
            assertEquals("pk_null__id", result);
        }

        @Test
        @DisplayName("빈 문자열은 'x'로 변환")
        void emptyStringToX() {
            String result = naming.pkName("", List.of(""));
            assertEquals("pk_x__x", result);
        }

        @Test
        @DisplayName("전부 특수문자는 'x'로 변환")
        void allSpecialCharsToX() {
            String result = naming.pkName("!!!", List.of("!!!"));
            assertEquals("pk_x__x", result);
        }

        @Test
        @DisplayName("비허용 문자는 '_'로 변환")
        void nonAllowedCharsToUnderscore() {
            String result = naming.pkName("A B", List.of("C-D"));
            assertEquals("pk_a_b__c_d", result);
        }

        @Test
        @DisplayName("연속 '_'는 단일 '_'로 병합")
        void consecutiveUnderscoresMerged() {
            String result = naming.pkName("A___B", List.of("C__D"));
            assertEquals("pk_a_b__c_d", result);
        }

        @Test
        @DisplayName("소문자 변환")
        void lowercaseConversion() {
            String result = naming.pkName("UPPERCASE", List.of("MixedCase"));
            assertEquals("pk_uppercase__mixedcase", result);
        }

        @Test
        @DisplayName("허용 문자: A-Z, a-z, 0-9, _")
        void allowedCharacters() {
            String result = naming.pkName("Valid_Name_123", List.of("column_456"));
            assertEquals("pk_valid_name_123__column_456", result);
        }
    }

    @Nested
    @DisplayName("길이 제한 (clampWithHash)")
    class LengthLimitTests {

        @Test
        @DisplayName("maxLength 이하면 그대로 반환")
        void withinMaxLength() {
            DefaultNaming n = new DefaultNaming(128);
            String result = n.pkName("Users", List.of("Id"));
            assertEquals("pk_users__id", result);
            assertTrue(result.length() <= 128);
        }

        @Test
        @DisplayName("maxLength 초과 시 hash로 절단")
        void exceedsMaxLengthClampedWithHash() {
            DefaultNaming n = new DefaultNaming(24);
            String longTable = "this_is_a_very_long_table_name";
            List<String> longCols = List.of("colA_very_long", "colB_very_long");

            String result = n.pkName(longTable, longCols);

            assertTrue(result.length() <= 24);
            assertTrue(result.contains("_")); // hash 구분자
        }

        @Test
        @DisplayName("동일 입력은 동일 hash 생성 (결정성)")
        void deterministicHash() {
            DefaultNaming n = new DefaultNaming(20);
            String longName = "very_long_table_name_that_exceeds_limit";

            String result1 = n.pkName(longName, List.of("col1", "col2"));
            String result2 = n.pkName(longName, List.of("col1", "col2"));

            assertEquals(result1, result2);
        }

        @Test
        @DisplayName("다른 입력은 다른 hash 생성")
        void differentInputDifferentHash() {
            DefaultNaming n = new DefaultNaming(20);
            String name1 = "very_long_table_name";
            String name2 = "very_long_table_name_x";

            String result1 = n.pkName(name1, List.of("col"));
            String result2 = n.pkName(name2, List.of("col"));

            assertNotEquals(result1, result2);
        }

        @Test
        @DisplayName("SHA-256 기반 안정적인 hash")
        void sha256BasedStableHash() {
            DefaultNaming n = new DefaultNaming(15);
            String longName = "extremely_long_name_for_testing_purposes";

            String result = n.pkName(longName, List.of("a", "b", "c"));

            // hash는 8자리 hex (4바이트)
            assertTrue(result.length() <= 15);
            assertTrue(result.matches(".*_[0-9a-f]{8}"));
        }
    }

    @Nested
    @DisplayName("컬럼 정렬 일관성")
    class ColumnSortingConsistencyTests {

        @Test
        @DisplayName("모든 메서드에서 정렬 보장")
        void sortingAcrossAllMethods() {
            List<String> cols1 = List.of("c", "b", "a");
            List<String> cols2 = List.of("a", "b", "c");

            assertEquals(naming.pkName("t", cols1), naming.pkName("t", cols2));
            assertEquals(naming.uqName("t", cols1), naming.uqName("t", cols2));
            assertEquals(naming.ixName("t", cols1), naming.ixName("t", cols2));
            assertEquals(naming.ckName("t", cols1), naming.ckName("t", cols2));
            assertEquals(naming.nnName("t", cols1), naming.nnName("t", cols2));
            assertEquals(naming.dfName("t", cols1), naming.dfName("t", cols2));
            assertEquals(naming.autoName("t", cols1), naming.autoName("t", cols2));
        }

        @Test
        @DisplayName("대소문자 무시 정렬 (CASE_INSENSITIVE_ORDER)")
        void caseInsensitiveSorting() {
            List<String> cols = List.of("B", "a", "C");
            String result = naming.pkName("t", cols);
            assertEquals("pk_t__a_b_c", result);
        }

        @Test
        @DisplayName("숫자와 문자 혼합 정렬")
        void mixedNumbersAndLetters() {
            List<String> cols = List.of("col10", "col2", "col1");
            String result = naming.pkName("t", cols);
            // 문자열 정렬: col1 < col10 < col2
            assertEquals("pk_t__col1_col10_col2", result);
        }
    }

    @Nested
    @DisplayName("극단적인 케이스")
    class ExtremeCasesTests {

        @Test
        @DisplayName("매우 많은 컬럼")
        void manyColumns() {
            List<String> cols = Arrays.asList(
                "col1", "col2", "col3", "col4", "col5",
                "col6", "col7", "col8", "col9", "col10"
            );
            String result = naming.pkName("table", cols);
            assertTrue(result.startsWith("pk_table__"));
            assertTrue(result.contains("col1"));
            assertTrue(result.contains("col10"));
        }

        @Test
        @DisplayName("특수문자만으로 구성된 입력")
        void onlySpecialCharacters() {
            String result = naming.pkName("@#$%", List.of("!@#", "$%^"));
            assertEquals("pk_x__x_x", result);
        }

        @Test
        @DisplayName("유니코드 문자")
        void unicodeCharacters() {
            String result = naming.pkName("테이블", List.of("컬럼"));
            // 유니코드 문자는 모두 '_'로 치환되고, 연속 '_'는 하나로 병합되어 'x'로 변환
            assertEquals("pk_x__x", result);
        }

        @Test
        @DisplayName("공백만 있는 문자열")
        void onlyWhitespace() {
            String result = naming.pkName("   ", List.of("   "));
            assertEquals("pk_x__x", result);
        }

        @Test
        @DisplayName("매우 긴 단일 컬럼명")
        void veryLongSingleColumn() {
            DefaultNaming n = new DefaultNaming(30);
            String longCol = "a".repeat(100);
            String result = n.pkName("t", List.of(longCol));
            assertTrue(result.length() <= 30);
        }
    }

    @Nested
    @DisplayName("실제 사용 시나리오")
    class RealWorldScenariosTests {

        @Test
        @DisplayName("User-Role 다대다 조인 테이블")
        void userRoleJoinTable() {
            String result = naming.joinTableName("users", "roles");
            assertEquals("jt_roles__users", result);
        }

        @Test
        @DisplayName("복합 PK를 가진 OrderItems")
        void orderItemsCompositePk() {
            String result = naming.pkName("order_items", List.of("order_id", "product_id"));
            assertEquals("pk_order_items__order_id_product_id", result);
        }

        @Test
        @DisplayName("Email 유니크 제약조건")
        void emailUniqueConstraint() {
            String result = naming.uqName("users", List.of("email"));
            assertEquals("uq_users__email", result);
        }

        @Test
        @DisplayName("테넌트별 격리를 위한 복합 유니크")
        void tenantIsolationCompositeUnique() {
            String result = naming.uqName("organizations", List.of("tenant_id", "name"));
            assertEquals("uq_organizations__name_tenant_id", result);
        }

        @Test
        @DisplayName("날짜 범위 인덱스")
        void dateRangeIndex() {
            String result = naming.ixName("orders", List.of("created_at", "updated_at"));
            assertEquals("ix_orders__created_at_updated_at", result);
        }

        @Test
        @DisplayName("양수 체크 제약조건")
        void positiveCheckConstraint() {
            CheckConstraint cc = createCheckConstraint("price > 0");
            String result = naming.ckName("products", cc);
            assertEquals("ck_products__price_0", result);
        }

        @Test
        @DisplayName("계층 구조 자기 참조 FK")
        void hierarchicalSelfReferencingFk() {
            String result = naming.fkName("categories", List.of("parent_id"),
                                         "categories", List.of("id"));
            assertEquals("fk_categories__parent_id__categories", result);
        }
    }

    @Nested
    @DisplayName("생성자 및 설정")
    class ConstructorAndConfigTests {

        @Test
        @DisplayName("다양한 maxLength 설정")
        void variousMaxLengths() {
            DefaultNaming n30 = new DefaultNaming(30);
            DefaultNaming n64 = new DefaultNaming(64);
            DefaultNaming n128 = new DefaultNaming(128);

            String longName = "very_long_table_name_for_testing";
            String result30 = n30.pkName(longName, List.of("col1", "col2"));
            String result64 = n64.pkName(longName, List.of("col1", "col2"));
            String result128 = n128.pkName(longName, List.of("col1", "col2"));

            assertTrue(result30.length() <= 30);
            assertTrue(result64.length() <= 64);
            assertTrue(result128.length() <= 128);
        }

        @Test
        @DisplayName("Oracle 최대 길이 (30자)")
        void oracleMaxLength() {
            DefaultNaming oracle = new DefaultNaming(30);
            String result = oracle.pkName("users", List.of("id"));
            assertTrue(result.length() <= 30);
        }

        @Test
        @DisplayName("PostgreSQL 최대 길이 (63자)")
        void postgresqlMaxLength() {
            DefaultNaming postgres = new DefaultNaming(63);
            String result = postgres.pkName("users", List.of("id"));
            assertTrue(result.length() <= 63);
        }

        @Test
        @DisplayName("MySQL 최대 길이 (64자)")
        void mysqlMaxLength() {
            DefaultNaming mysql = new DefaultNaming(64);
            String result = mysql.pkName("users", List.of("id"));
            assertTrue(result.length() <= 64);
        }
    }

    // Helper method
    private static CheckConstraint createCheckConstraint(String expr) {
        return (CheckConstraint) Proxy.newProxyInstance(
                CheckConstraint.class.getClassLoader(),
                new Class<?>[]{CheckConstraint.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("constraint")) return expr;
                    if (method.getName().equals("name")) return "";
                    if (method.getName().equals("options")) return "";
                    if (method.getName().equals("annotationType"))
                        return (Class<? extends Annotation>) CheckConstraint.class;
                    return method.getDefaultValue();
                }
        );
    }
}
