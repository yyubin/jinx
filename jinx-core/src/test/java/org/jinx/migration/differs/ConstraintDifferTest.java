package org.jinx.migration.differs;

import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintDifferTest {

    private static EntityModel entity(String name, Map<String, ConstraintModel> constraints) {
        return EntityModel.builder()
                .entityName(name)
                .tableName(name)
                .constraints(constraints != null ? new LinkedHashMap<>(constraints) : new LinkedHashMap<>())
                .build();
    }

    private static ConstraintModel cons(String name, ConstraintType type, List<String> columns) {
        return ConstraintModel.builder()
                .name(name)
                .tableName("T")
                .type(type)
                .columns(new ArrayList<>(columns))
                .build();
    }

    private static DiffResult.ModifiedEntity newResult(EntityModel e) {
        return DiffResult.ModifiedEntity.builder()
                .newEntity(e)
                .build();
    }

    @Test
    @DisplayName("ADDED: 새 PK 제약 추가")
    void added_pk_constraint() {
        var oldE = entity("T", Map.of());
        var newE = entity("T", Map.of(
                "PK_T", cons("PK_T", ConstraintType.PRIMARY_KEY, List.of("ID"))
        ));

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        assertThat(result.getConstraintDiffs()).hasSize(1);
        var diff = result.getConstraintDiffs().get(0);
        assertThat(diff.getType()).isEqualTo(DiffResult.ConstraintDiff.Type.ADDED);
        assertThat(diff.getConstraint().getName()).isEqualTo("PK_T");
    }

    @Test
    @DisplayName("DROPPED: 기존 UNIQUE 제약 삭제")
    void dropped_unique_constraint() {
        var oldE = entity("T", Map.of(
                "UQ_T_A_B", cons("UQ_T_A_B", ConstraintType.UNIQUE, List.of("A", "B"))
        ));
        var newE = entity("T", Map.of());

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        assertThat(result.getConstraintDiffs()).hasSize(1);
        var diff = result.getConstraintDiffs().get(0);
        assertThat(diff.getType()).isEqualTo(DiffResult.ConstraintDiff.Type.DROPPED);
        assertThat(diff.getConstraint().getName()).isEqualTo("UQ_T_A_B");
    }

    @Test
    @DisplayName("MODIFIED(이름 변경): 타입/컬럼 동일 + 이름만 변경 → MODIFIED")
    void renamed_constraint_reports_modified_due_to_name_change() {
        var oldE = entity("T", Map.of(
                "PK_OLD", cons("PK_OLD", ConstraintType.PRIMARY_KEY, List.of("ID"))
        ));
        var newE = entity("T", Map.of(
                "PK_NEW", cons("PK_NEW", ConstraintType.PRIMARY_KEY, List.of("ID"))
        ));

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        assertThat(result.getConstraintDiffs()).hasSize(1);
        var diff = result.getConstraintDiffs().get(0);
        assertThat(diff.getType()).isEqualTo(DiffResult.ConstraintDiff.Type.MODIFIED);
        assertThat(diff.getOldConstraint().getName()).isEqualTo("PK_OLD");
        assertThat(diff.getConstraint().getName()).isEqualTo("PK_NEW");
        assertThat(diff.getChangeDetail()).contains("name changed from PK_OLD to PK_NEW");
    }

    @Test
    @DisplayName("컬럼 순서 변경은 동일로 간주(집합 비교) → 변경 없음")
    void only_column_order_changed_is_not_modified() {
        var oldE = entity("T", Map.of(
                "UQ_AB", cons("UQ_AB", ConstraintType.UNIQUE, List.of("A", "B"))
        ));
        var newE = entity("T", Map.of(
                "UQ_AB", cons("UQ_AB", ConstraintType.UNIQUE, List.of("B", "A"))
        ));

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        // 타입 동일 + 컬럼 집합 동일 + 이름 동일 -> 변경 없음
        assertThat(result.getConstraintDiffs()).isEmpty();
    }

    @Test
    @DisplayName("CHECK 절 변경 → MODIFIED")
    void check_clause_change_is_detected_as_modified() {
        var oldCk = ConstraintModel.builder()
                .name("CK_POSITIVE")
                .tableName("T")
                .type(ConstraintType.CHECK)
                .columns(new ArrayList<>(List.of("A")))
                .checkClause(Optional.of("( A  >  0 )"))
                .build();

        var newCk = ConstraintModel.builder()
                .name("CK_POSITIVE")
                .tableName("T")
                .type(ConstraintType.CHECK)
                .columns(new ArrayList<>(List.of("A")))
                .checkClause(Optional.of("a>=1")) // 의미/표현 달라짐
                .build();

        var oldE = entity("T", Map.of("CK_POSITIVE", oldCk));
        var newE = entity("T", Map.of("CK_POSITIVE", newCk));

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        assertThat(result.getConstraintDiffs()).hasSize(1);
        var diff = result.getConstraintDiffs().get(0);
        assertThat(diff.getType()).isEqualTo(DiffResult.ConstraintDiff.Type.MODIFIED);
        assertThat(diff.getChangeDetail()).contains("checkClause changed");
    }


    @Test
    @DisplayName("INDEX 이름만 변경(다른 속성은 동일) → MODIFIED(이름 변경)")
    void index_rename_is_modified_by_name_change() {
        var oldIdx = ConstraintModel.builder()
                .name("IX_OLD")
                .tableName("T")
                .type(ConstraintType.INDEX)
                .columns(new ArrayList<>(List.of("A", "B")))
                .options(Optional.of("NONUNIQUE")) // 현 구현은 비교에 사용 안 함
                .build();

        var newIdx = ConstraintModel.builder()
                .name("IX_NEW")
                .tableName("T")
                .type(ConstraintType.INDEX)
                .columns(new ArrayList<>(List.of("A", "B")))
                .options(Optional.of("UNIQUE")) // 변경되어도 이름 비교 외엔 반영 안 됨
                .build();

        var oldE = entity("T", Map.of("IX_OLD", oldIdx));
        var newE = entity("T", Map.of("IX_NEW", newIdx));

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        assertThat(result.getConstraintDiffs()).hasSize(1);
        var diff = result.getConstraintDiffs().get(0);
        assertThat(diff.getType()).isEqualTo(DiffResult.ConstraintDiff.Type.MODIFIED);
        assertThat(diff.getOldConstraint().getName()).isEqualTo("IX_OLD");
        assertThat(diff.getConstraint().getName()).isEqualTo("IX_NEW");
        assertThat(diff.getChangeDetail()).contains("name changed from IX_OLD to IX_NEW");
    }

    @Test
    @DisplayName("동형 제약 다중 리네임: 1:1로 페어링되어 모두 MODIFIED로 기록")
    void multiple_renames_pairing() {
        var oldE = entity("T", new LinkedHashMap<>(Map.of(
                "UQ_A", cons("UQ_A", ConstraintType.UNIQUE, List.of("A")),
                "UQ_B", cons("UQ_B", ConstraintType.UNIQUE, List.of("B"))
        )));
        var newE = entity("T", new LinkedHashMap<>(Map.of(
                "UQ_A_NEW", cons("UQ_A_NEW", ConstraintType.UNIQUE, List.of("A")),
                "UQ_B_NEW", cons("UQ_B_NEW", ConstraintType.UNIQUE, List.of("B"))
        )));

        var differ = new ConstraintDiffer();
        var result = newResult(newE);
        differ.diff(oldE, newE, result);

        assertThat(result.getConstraintDiffs()).hasSize(2);
        assertThat(result.getConstraintDiffs())
                .allMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.MODIFIED);
        assertThat(result.getConstraintDiffs())
                .extracting(d -> d.getConstraint().getName())
                .containsExactlyInAnyOrder("UQ_A_NEW", "UQ_B_NEW");
    }
}
