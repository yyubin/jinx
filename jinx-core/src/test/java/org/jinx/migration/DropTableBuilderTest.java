package org.jinx.migration;

import org.jinx.migration.contributor.TableContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DropTableBuilderTest {

    // 테스트 전용 TableContributor: 우선순위와 출력 조각만 관리
    static class Piece implements TableContributor {
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
        DropTableBuilder b = new DropTableBuilder(dialect);

        b.add(new Piece(20, "B\n"))
                .add(new Piece(10, "A\n"))
                .add(new Piece(30, "C"));

        String sql = b.build();
        assertEquals("A\nB\nC", sql);
    }

    @Test
    @DisplayName("빈 컨트리뷰터 리스트면 빈 문자열을 반환한다")
    void empty_returns_empty_string() {
        DdlDialect dialect = mock(DdlDialect.class);
        DropTableBuilder b = new DropTableBuilder(dialect);
        assertEquals("", b.build());
    }

    @Test
    @DisplayName("add는 체이닝을 지원한다")
    void add_is_chainable() {
        DdlDialect dialect = mock(DdlDialect.class);
        DropTableBuilder b = new DropTableBuilder(dialect);
        DropTableBuilder returned = b.add(new Piece(0, "X"));
        assertSame(b, returned);
    }

    @Test
    @DisplayName("동일 priority 간 상대 순서는 보장하지 않는다(정렬 정책 확인)")
    void same_priority_has_no_order_guarantee() {
        DdlDialect dialect = mock(DdlDialect.class);
        DropTableBuilder b = new DropTableBuilder(dialect);

        b.add(new Piece(10, "A"))
                .add(new Piece(10, "B"));

        String sql = b.build();
        assertTrue(sql.equals("AB") || sql.equals("BA"));
    }

    @Test
    @DisplayName("말단 공백/개행은 별도로 trim하지 않는다(AlterTableBuilder와 정책 차이)")
    void trailing_whitespace_is_preserved() {
        DdlDialect dialect = mock(DdlDialect.class);
        DropTableBuilder b = new DropTableBuilder(dialect);

        b.add(new Piece(0, "DROP INDEX X;\n"))
                .add(new Piece(1, "DROP TABLE \"t\";\n\n"));

        String sql = b.build();
        assertEquals("DROP INDEX X;\nDROP TABLE \"t\";\n\n", sql);
    }
}
