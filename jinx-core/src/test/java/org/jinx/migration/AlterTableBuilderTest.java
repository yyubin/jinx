package org.jinx.migration;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.SqlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlterTableBuilderTest {

    // 테스트 전용 컨트리뷰터: 우선순위와 출력 조각만 가짐
    static class Piece implements DdlContributor {
        private final int p;
        private final String out;
        Piece(int p, String out) { this.p = p; this.out = out; }
        @Override public int priority() { return p; }
        @Override public void contribute(StringBuilder sb, DdlDialect dialect) { sb.append(out); }
    }

    @Test
    @DisplayName("우선순위 오름차순으로 정렬되어 순서대로 연결된다")
    void builds_in_priority_order() {
        DdlDialect dialect = mock(DdlDialect.class);
        AlterTableBuilder b = new AlterTableBuilder("users", dialect);

        // priority: 낮을수록 먼저
        b.add(new Piece(20, "B\n"))
                .add(new Piece(10, "A\n"))
                .add(new Piece(30, "C")); // 마지막은 개행 없음

        String sql = b.build();
        assertEquals("A\nB\nC", sql);
    }

    @Test
    @DisplayName("빌드 결과는 말단 공백/개행이 trim 처리된다")
    void trailing_whitespace_is_trimmed() {
        DdlDialect dialect = mock(DdlDialect.class);
        AlterTableBuilder b = new AlterTableBuilder("t", dialect);

        b.add(new Piece(0, "X  \n \n")); // 끝에 공백/개행 다수
        assertEquals("X", b.build());
    }

    @Test
    @DisplayName("빈 유닛이어도 빈 문자열을 반환한다")
    void empty_units_returns_empty_string() {
        DdlDialect dialect = mock(DdlDialect.class);
        AlterTableBuilder b = new AlterTableBuilder("empty", dialect);
        assertEquals("", b.build());
    }

    @Test
    @DisplayName("add는 체이닝을 지원하며, getter들이 주입값을 그대로 노출한다")
    void add_is_chainable_and_getters_expose_values() {
        DdlDialect dialect = mock(DdlDialect.class);
        AlterTableBuilder b = new AlterTableBuilder("orders", dialect);

        AlterTableBuilder returned = b.add(new Piece(5, "X"));
        assertSame(b, returned, "add는 this를 반환해야 체이닝이 가능합니다");
        assertEquals("orders", b.getTableName());
        assertSame(dialect, b.getDialect());
        assertEquals(1, b.getUnits().size());
    }

    @Test
    @DisplayName("동일 priority 컨트리뷰터 간 상대 순서는 보장하지 않는다(정렬 정책 확인용)")
    void same_priority_has_no_order_guarantee() {
        DdlDialect dialect = mock(DdlDialect.class);
        AlterTableBuilder b = new AlterTableBuilder("t", dialect);

        b.add(new Piece(10, "A"))
                .add(new Piece(10, "B"));

        // 같은 priority의 상대 순서는 빌더 정책상 미보장
        // 다만 두 조각이 모두 포함되는지만 확인
        String sql = b.build();
        assertTrue(sql.equals("AB") || sql.equals("BA"));
    }
}
