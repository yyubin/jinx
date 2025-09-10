package org.jinx.naming;

import jakarta.persistence.CheckConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultNamingTest {

    @Test
    @DisplayName("foreignKeyColumnName: owner_referencedPk 규칙 및 정규화")
    void foreignKeyColumnName_basic() {
        DefaultNaming n = new DefaultNaming(128);
        assertThat(n.foreignKeyColumnName("Order", "CustomerID"))
                .isEqualTo("order_customerid");

        // 특수문자/공백/대소문자 -> 정규화(소문자 + 비허용문자 '_')
        assertThat(n.foreignKeyColumnName("Ord er!", "Custo-mer ID"))
                .isEqualTo("ord_er__custo_mer_id");
    }

    @Test
    @DisplayName("joinTableName: 좌우 입력 순서 무관, 정렬 후 jt_a__b 형태")
    void joinTableName_sorted() {
        DefaultNaming n = new DefaultNaming(128);

        String ab = n.joinTableName("Author", "Book");
        String ba = n.joinTableName("Book", "Author");

        assertThat(ab).isEqualTo("jt_author__book");
        assertThat(ba).isEqualTo("jt_author__book");

        // 대소문자 섞여도 정규화 후 일관
        assertThat(n.joinTableName("AUTHOR", "book"))
                .isEqualTo("jt_author__book");
    }

    @Test
    @DisplayName("pkName: pk_<table>__<cols(sorted)> 규칙 + 정규화")
    void pkName_basic_and_sorted() {
        DefaultNaming n = new DefaultNaming(128);

        String name1 = n.pkName("Products", List.of("Id", "Sku"));
        String name2 = n.pkName("Products", List.of("Sku", "Id")); // 순서 달라도 동일

        assertThat(name1).isEqualTo("pk_products__id_sku");
        assertThat(name2).isEqualTo("pk_products__id_sku");
    }

    @Test
    @DisplayName("uqName/ixName/nnName/dfName/autoName: 접두사만 다르고 동일 규칙 적용")
    void other_constraint_prefixes() {
        DefaultNaming n = new DefaultNaming(128);
        List<String> cols = List.of("A", "b", "c2");

        assertThat(n.uqName("T", cols)).isEqualTo("uq_t__a_b_c2");
        assertThat(n.ixName("T", cols)).isEqualTo("ix_t__a_b_c2");
        assertThat(n.nnName("T", cols)).isEqualTo("nn_t__a_b_c2");
        assertThat(n.dfName("T", cols)).isEqualTo("df_t__a_b_c2");
        assertThat(n.autoName("T", cols)).isEqualTo("cn_t__a_b_c2");
    }

    @Test
    @DisplayName("ckName(List): ck_<table>__<cols(sorted)> 규칙")
    void ckName_withColumns() {
        DefaultNaming n = new DefaultNaming(128);

        String ck1 = n.ckName("Orders", List.of("Qty", "Price"));
        String ck2 = n.ckName("Orders", List.of("Price", "Qty"));

        assertThat(ck1).isEqualTo("ck_orders__price_qty"); // 정렬로 price 먼저
        assertThat(ck2).isEqualTo("ck_orders__price_qty");
    }

    @Test
    @DisplayName("ckName(CheckConstraint): constraint 문자열을 정규화하여 접미로 사용")
    void ckName_withAnnotation() {
        DefaultNaming n = new DefaultNaming(128);
        CheckConstraint cc = checkConstraintOf("price > 0 AND qty >= 1");

        String ck = n.ckName("Orders", cc);
        // 공백/기호 → '_' 치환, 소문자화
        assertThat(ck).isEqualTo("ck_orders__price_0_and_qty_1");
    }

    @Test
    @DisplayName("정규화 규칙: null → 'null', 빈문자열 → 'x', 비허용문자 → '_' 치환, 소문자")
    void normalization_rules_via_methods() {
        DefaultNaming n = new DefaultNaming(128);

        String name = n.pkName(null, List.of("", "A B", "!!!"));
        assertThat(name).isEqualTo("pk_null__a_b_x_x");
    }

    @Test
    @DisplayName("clampWithHash: maxLength 초과 시 접두 + '_' + hex(hash) 형태로 절단")
    void clampWithHash_applies_and_is_deterministic() {
        // 아주 짧게 설정하여 clamp를 강제
        DefaultNaming n = new DefaultNaming(24);

        String longTable = "this_is_a_very_long_table_name";
        List<String> longCols = List.of("colA_very_long", "colB_very_long");

        String pk1 = n.pkName(longTable, longCols);
        String pk2 = n.pkName(longTable, longCols); // 동일 입력 -> 동일 결과

        assertThat(pk1).isEqualTo(pk2);
        assertThat(pk1.length()).isLessThanOrEqualTo(24);
        assertThat(pk1).contains("_"); // 해시 앞 구분자

        // 유사하지만 다른 입력 -> 다른 해시 기대
        String pk3 = n.pkName(longTable + "_x", longCols);
        assertThat(pk3).isNotEqualTo(pk1);
    }

    @Test
    @DisplayName("모든 메서드: 입력 순서가 달라도 결정적 결과(정렬 보장)")
    void determinism_via_column_sorting() {
        DefaultNaming n = new DefaultNaming(128);
        List<String> c1 = List.of("b", "A", "c");
        List<String> c2 = List.of("c", "b", "A");

        assertThat(n.fkName("child", c1, "parent", c2))
                .isEqualTo(n.fkName("child", c2, "parent", c1));

        assertThat(n.uqName("t", c1)).isEqualTo(n.uqName("t", c2));
        assertThat(n.ixName("t", c1)).isEqualTo(n.ixName("t", c2));
        assertThat(n.nnName("t", c1)).isEqualTo(n.nnName("t", c2));
        assertThat(n.dfName("t", c1)).isEqualTo(n.dfName("t", c2));
        assertThat(n.autoName("t", c1)).isEqualTo(n.autoName("t", c2));
    }

    @Test
    @DisplayName("fkName: fk_child__<childCols(sorted)>__parent 규칙 및 정규화")
    void fkName_format() {
        DefaultNaming n = new DefaultNaming(128);
        String fk = n.fkName("Order Items", List.of("Product ID", "order_id"), "Products", List.of("ID"));
        assertThat(fk).isEqualTo("fk_order_items__order_id_product_id__products");
    }

    private static CheckConstraint checkConstraintOf(String expr) {
        // 동적 프록시로 @CheckConstraint 인스턴스 구성
        return (CheckConstraint) Proxy.newProxyInstance(
                CheckConstraint.class.getClassLoader(),
                new Class<?>[]{CheckConstraint.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("constraint")) return expr;
                    if (method.getName().equals("name")) return "";
                    if (method.getName().equals("validationAppliesTo")) return "";
                    if (method.getName().equals("message")) return "";
                    if (method.getName().equals("groups")) return new Class<?>[0];
                    if (method.getName().equals("payload")) return new Class<?>[0];
                    if (method.getName().equals("annotationType")) return (Class<? extends Annotation>) CheckConstraint.class;
                    return method.getDefaultValue();
                }
        );
    }
}
