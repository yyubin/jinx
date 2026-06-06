package org.jinx.migration;

import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DependencyResolver 위상정렬 테스트")
class DependencyResolverTest {

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** FK 관계를 가진 EntityModel 생성. referencedTables 순서대로 관계가 등록됩니다. */
    private static EntityModel table(String tableName, String... referencedTables) {
        Map<String, RelationshipModel> rels = new LinkedHashMap<>();
        for (String ref : referencedTables) {
            String key = "fk_" + tableName.toLowerCase() + "_to_" + ref.toLowerCase();
            rels.put(key, RelationshipModel.builder()
                    .constraintName(key)
                    .referencedTable(ref)
                    .build());
        }
        return EntityModel.builder().tableName(tableName).relationships(rels).build();
    }

    /** FK 없는 EntityModel 생성. */
    private static EntityModel table(String tableName) {
        return EntityModel.builder().tableName(tableName).build();
    }

    /** noConstraint=true인 FK를 가진 EntityModel 생성. */
    private static EntityModel tableNoConstraint(String tableName, String referencedTable) {
        String key = "fk_" + tableName.toLowerCase() + "_to_" + referencedTable.toLowerCase();
        return EntityModel.builder()
                .tableName(tableName)
                .relationships(Map.of(key, RelationshipModel.builder()
                        .constraintName(key)
                        .referencedTable(referencedTable)
                        .noConstraint(true)
                        .build()))
                .build();
    }

    /** 동일 부모에 FK 2개를 가진 EntityModel 생성 (중복 엣지 검증용). */
    private static EntityModel tableWithDuplicateFk(String tableName, String referencedTable) {
        return EntityModel.builder()
                .tableName(tableName)
                .relationships(Map.of(
                        "fk_1", RelationshipModel.builder()
                                .constraintName("fk_1")
                                .referencedTable(referencedTable)
                                .build(),
                        "fk_2", RelationshipModel.builder()
                                .constraintName("fk_2")
                                .referencedTable(referencedTable)
                                .build()
                ))
                .build();
    }

    private static List<String> names(List<EntityModel> entities) {
        return entities.stream().map(EntityModel::getTableName).collect(Collectors.toList());
    }

    // ── null / 경계 케이스 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("경계 케이스")
    class EdgeCases {

        @Test
        @DisplayName("null 입력 시 빈 리스트 반환")
        void null_returns_empty() {
            assertThat(DependencyResolver.sortByFkDependency(null)).isEmpty();
        }

        @Test
        @DisplayName("빈 리스트 입력 시 동일 참조 반환")
        void empty_list_returns_same() {
            List<EntityModel> empty = List.of();
            assertThat(DependencyResolver.sortByFkDependency(empty)).isSameAs(empty);
        }

        @Test
        @DisplayName("단일 테이블 입력 시 동일 참조 반환")
        void single_table_returns_same() {
            List<EntityModel> single = List.of(table("A"));
            assertThat(DependencyResolver.sortByFkDependency(single)).isSameAs(single);
        }

        @Test
        @DisplayName("tableName이 null인 엔티티는 무시되고 나머지를 반환")
        void null_tableName_is_skipped() {
            EntityModel noName = EntityModel.builder().build(); // tableName = null
            EntityModel a = table("A");
            List<EntityModel> result = DependencyResolver.sortByFkDependency(List.of(noName, a));
            // null-name 엔티티 1개만 scope에서 제외 → size <= 1 → 원본 반환
            assertThat(result).containsExactly(noName, a);
        }
    }

    // ── FK 없는 경우 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("FK 의존성 없음")
    class NoFkDependency {

        @Test
        @DisplayName("FK 없는 두 테이블은 입력 순서 유지")
        void no_fk_preserves_order() {
            List<EntityModel> tables = List.of(table("A"), table("B"));
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("A", "B");
        }

        @Test
        @DisplayName("FK 없는 세 테이블은 입력 순서 유지")
        void no_fk_three_tables_preserves_order() {
            List<EntityModel> tables = List.of(table("A"), table("B"), table("C"));
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("A", "B", "C");
        }
    }

    // ── 단순 의존 관계 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("단순 FK 의존 정렬")
    class SimpleFkOrdering {

        @Test
        @DisplayName("A→B: 부모 B가 자식 A보다 먼저 나온다")
        void parent_before_child() {
            // A has FK to B (A depends on B, B is parent)
            List<EntityModel> tables = List.of(table("A", "B"), table("B"));
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("B", "A");
        }

        @Test
        @DisplayName("역순 입력 [B, A] A→B: 정렬 후에도 B가 앞에 나온다")
        void parent_before_child_reversed_input() {
            List<EntityModel> tables = List.of(table("B"), table("A", "B"));
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("B", "A");
        }

        @Test
        @DisplayName("선형 체인 A→B→C: 정렬 결과는 [C, B, A]")
        void linear_chain() {
            // C ← B ← A
            List<EntityModel> tables = List.of(
                    table("A", "B"),
                    table("B", "C"),
                    table("C")
            );
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("C", "B", "A");
        }

        @Test
        @DisplayName("선형 체인 역순 입력 [C, B, A]: 정렬 결과는 [C, B, A]")
        void linear_chain_reversed_input() {
            List<EntityModel> tables = List.of(
                    table("C"),
                    table("B", "C"),
                    table("A", "B")
            );
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("C", "B", "A");
        }
    }

    // ── 복잡한 위상 구조 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("복잡한 위상 구조")
    class ComplexTopology {

        @Test
        @DisplayName("다이아몬드 A→C, B→C: C가 A, B보다 먼저 나온다")
        void diamond_shape() {
            List<EntityModel> tables = List.of(
                    table("A", "C"),
                    table("B", "C"),
                    table("C")
            );
            List<String> result = names(DependencyResolver.sortByFkDependency(tables));
            assertThat(result.indexOf("C")).isLessThan(result.indexOf("A"));
            assertThat(result.indexOf("C")).isLessThan(result.indexOf("B"));
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("두 독립 체인 {A→B}, {C→D}: 각 체인 내 부모가 자식보다 먼저 나온다")
        void two_independent_chains() {
            List<EntityModel> tables = List.of(
                    table("A", "B"),
                    table("B"),
                    table("C", "D"),
                    table("D")
            );
            List<String> result = names(DependencyResolver.sortByFkDependency(tables));
            assertThat(result.indexOf("B")).isLessThan(result.indexOf("A"));
            assertThat(result.indexOf("D")).isLessThan(result.indexOf("C"));
            assertThat(result).hasSize(4);
        }

        @Test
        @DisplayName("join table: A→C, B→C, AB(A,B 동시 참조): C가 가장 먼저, A와 B는 AB보다 앞")
        void join_table_ordering() {
            // AB는 A와 B를 동시에 참조하는 조인 테이블
            List<EntityModel> tables = List.of(
                    table("AB", "A", "B"),
                    table("A", "C"),
                    table("B", "C"),
                    table("C")
            );
            List<String> result = names(DependencyResolver.sortByFkDependency(tables));
            assertThat(result.indexOf("C")).isLessThan(result.indexOf("A"));
            assertThat(result.indexOf("C")).isLessThan(result.indexOf("B"));
            assertThat(result.indexOf("A")).isLessThan(result.indexOf("AB"));
            assertThat(result.indexOf("B")).isLessThan(result.indexOf("AB"));
            assertThat(result).hasSize(4);
        }
    }

    // ── DROP 순서 (.reversed()) ───────────────────────────────────────────

    @Nested
    @DisplayName("DROP 순서: .reversed() 적용")
    class DropOrdering {

        @Test
        @DisplayName("A→B 체인 reversed: 자식 A가 부모 B보다 먼저 드롭")
        void drop_order_simple() {
            List<EntityModel> tables = List.of(table("A", "B"), table("B"));
            List<String> dropOrder = names(
                    DependencyResolver.sortByFkDependency(tables).reversed()
            );
            assertThat(dropOrder).containsExactly("A", "B");
        }

        @Test
        @DisplayName("선형 체인 A→B→C reversed: 드롭 순서는 [A, B, C]")
        void drop_order_chain() {
            List<EntityModel> tables = List.of(
                    table("A", "B"),
                    table("B", "C"),
                    table("C")
            );
            List<String> dropOrder = names(
                    DependencyResolver.sortByFkDependency(tables).reversed()
            );
            assertThat(dropOrder).containsExactly("A", "B", "C");
        }
    }

    // ── FK 무시 케이스 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("FK 무시 케이스")
    class FkIgnored {

        @Test
        @DisplayName("noConstraint=true인 관계는 의존성에서 제외 → 순서 영향 없음")
        void no_constraint_fk_ignored() {
            // A가 B를 noConstraint FK로 참조: 의존성 없음
            List<EntityModel> tables = List.of(
                    tableNoConstraint("A", "B"),
                    table("B")
            );
            // B → A 순서 강제가 없으므로 입력 순서 [A, B] 유지
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("A", "B");
        }

        @Test
        @DisplayName("자기 참조(A→A)는 무시하고 다른 의존성은 정상 처리")
        void self_reference_ignored() {
            EntityModel selfRef = EntityModel.builder()
                    .tableName("A")
                    .relationships(Map.of(
                            "fk_self", RelationshipModel.builder()
                                    .constraintName("fk_self")
                                    .referencedTable("A")  // 자기 참조
                                    .build()
                    ))
                    .build();
            EntityModel b = table("B");
            List<EntityModel> tables = List.of(selfRef, b);
            // 자기 참조는 무시 → A와 B는 독립 → 입력 순서 유지
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("A", "B");
        }

        @Test
        @DisplayName("입력 집합 밖 테이블 참조는 무시 → 나머지 순서 영향 없음")
        void reference_outside_scope_ignored() {
            // A가 EXTERNAL을 참조하지만 EXTERNAL은 입력 목록에 없음
            List<EntityModel> tables = List.of(
                    table("A", "EXTERNAL"),
                    table("B")
            );
            // 범위 외 참조 무시 → A, B는 독립 → 입력 순서 유지
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("A", "B");
        }

        @Test
        @DisplayName("referencedTable이 null인 관계는 무시")
        void null_referenced_table_ignored() {
            EntityModel a = EntityModel.builder()
                    .tableName("A")
                    .relationships(Map.of(
                            "fk_null_ref", RelationshipModel.builder()
                                    .constraintName("fk_null_ref")
                                    .referencedTable(null)
                                    .build()
                    ))
                    .build();
            List<EntityModel> tables = List.of(a, table("B"));
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("A", "B");
        }
    }

    // ── 중복 엣지 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("중복 FK 엣지 처리")
    class DuplicateFkEdge {

        @Test
        @DisplayName("A가 B에 대한 FK 2개 보유: in-degree가 중복 카운트되지 않아 정상 정렬")
        void duplicate_fk_to_same_parent_sorts_correctly() {
            // A has 2 FKs both pointing to B → should still sort as [B, A]
            List<EntityModel> tables = List.of(tableWithDuplicateFk("A", "B"), table("B"));
            assertThat(names(DependencyResolver.sortByFkDependency(tables)))
                    .containsExactly("B", "A");
        }

        @Test
        @DisplayName("중복 FK reversed: DROP 순서도 올바름")
        void duplicate_fk_drop_order() {
            List<EntityModel> tables = List.of(tableWithDuplicateFk("A", "B"), table("B"));
            List<String> dropOrder = names(
                    DependencyResolver.sortByFkDependency(tables).reversed()
            );
            assertThat(dropOrder).containsExactly("A", "B");
        }
    }

    // ── 사이클 감지 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("사이클 감지 및 fallback")
    class CycleDetection {

        @Test
        @DisplayName("사이클 A⇄B: 원본 리스트를 그대로 반환")
        void cycle_returns_original_list() {
            List<EntityModel> tables = List.of(
                    table("A", "B"),
                    table("B", "A")
            );
            List<EntityModel> result = DependencyResolver.sortByFkDependency(tables);
            // 사이클 → fallback: 원본 참조 반환
            assertThat(result).isSameAs(tables);
        }

        @Test
        @DisplayName("사이클 A→B→C→A: 원본 리스트를 그대로 반환")
        void three_way_cycle_returns_original_list() {
            List<EntityModel> tables = List.of(
                    table("A", "B"),
                    table("B", "C"),
                    table("C", "A")
            );
            assertThat(DependencyResolver.sortByFkDependency(tables)).isSameAs(tables);
        }

        @Test
        @DisplayName("사이클 감지 시 System.err에 경고 메시지가 출력된다")
        void cycle_logs_warning_to_stderr() {
            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured));
            try {
                List<EntityModel> tables = List.of(
                        table("A", "B"),
                        table("B", "A")
                );
                DependencyResolver.sortByFkDependency(tables);
            } finally {
                System.setErr(originalErr);
            }
            assertThat(captured.toString())
                    .contains("[jinx] WARNING")
                    .contains("cycle");
        }

        @Test
        @DisplayName("사이클이 없는 부분은 정렬, 사이클이 있는 부분은 fallback (전체 원본 반환)")
        void partial_cycle_still_returns_original() {
            // D는 독립, A⇄B⇄C는 사이클 → 전체 fallback
            List<EntityModel> tables = List.of(
                    table("A", "B"),
                    table("B", "C"),
                    table("C", "A"),
                    table("D")
            );
            assertThat(DependencyResolver.sortByFkDependency(tables)).isSameAs(tables);
        }
    }

    // ── 대소문자 정규화 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("테이블명 대소문자 정규화")
    class CaseNormalization {

        @Test
        @DisplayName("FK referencedTable이 대문자여도 tableName 소문자와 동일하게 매칭")
        void case_insensitive_matching() {
            // tableName "orders" (소문자), referencedTable "CUSTOMERS" (대문자)
            List<EntityModel> tables = List.of(
                    table("orders", "CUSTOMERS"),
                    table("customers")
            );
            List<String> result = names(DependencyResolver.sortByFkDependency(tables));
            assertThat(result.indexOf("customers")).isLessThan(result.indexOf("orders"));
        }

        @Test
        @DisplayName("혼합 케이스 테이블명: 동일 테이블로 인식")
        void mixed_case_tables() {
            List<EntityModel> tables = List.of(
                    table("Order", "Customer"),
                    table("CUSTOMER")
            );
            List<String> result = names(DependencyResolver.sortByFkDependency(tables));
            // CUSTOMER (소문자: "customer")가 Order (소문자: "order") 앞에 나와야 함
            assertThat(result.indexOf("CUSTOMER")).isLessThan(result.indexOf("Order"));
        }
    }
}
