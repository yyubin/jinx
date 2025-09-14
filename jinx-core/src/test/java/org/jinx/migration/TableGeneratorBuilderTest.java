package org.jinx.migration;

import org.jinx.migration.contributor.TableGeneratorContributor;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TableGeneratorBuilderTest {

    static class Piece implements TableGeneratorContributor {
        private final int p;
        private final String out;
        Piece(int p, String out) { this.p = p; this.out = out; }
        @Override public int priority() { return p; }
        @Override public void contribute(StringBuilder sb, TableGeneratorDialect dialect) { sb.append(out); }
    }

    @Test
    @DisplayName("우선순위 오름차순으로 정렬되어 순서대로 연결된다")
    void builds_in_priority_order() {
        TableGeneratorDialect dialect = mock(TableGeneratorDialect.class);
        TableGeneratorBuilder b = new TableGeneratorBuilder(dialect);

        b.add(new Piece(20, "B\n"))
                .add(new Piece(10, "A\n"))
                .add(new Piece(30, "C"));

        String sql = b.build();
        assertEquals("A\nB\nC", sql);
    }

    @Test
    @DisplayName("빌드 결과는 말단 공백/개행이 trim 처리된다")
    void trailing_whitespace_is_trimmed() {
        TableGeneratorDialect dialect = mock(TableGeneratorDialect.class);
        TableGeneratorBuilder b = new TableGeneratorBuilder(dialect);

        b.add(new Piece(0, "X  \n \n"));
        assertEquals("X", b.build());
    }

    @Test
    @DisplayName("빈 유닛이면 빈 문자열을 반환한다")
    void empty_units_returns_empty_string() {
        TableGeneratorDialect dialect = mock(TableGeneratorDialect.class);
        TableGeneratorBuilder b = new TableGeneratorBuilder(dialect);
        assertEquals("", b.build());
    }

    @Test
    @DisplayName("add는 체이닝을 지원하고 getter가 주입값을 노출한다")
    void add_is_chainable_and_getters() {
        TableGeneratorDialect dialect = mock(TableGeneratorDialect.class);
        TableGeneratorBuilder b = new TableGeneratorBuilder(dialect);

        TableGeneratorBuilder returned = b.add(new Piece(5, "X"));
        assertSame(b, returned);
        assertSame(dialect, b.getDialect());
        assertEquals(1, b.getUnits().size());
    }

    @Test
    @DisplayName("동일 priority 간 상대 순서는 보장하지 않는다(정렬 정책 확인)")
    void same_priority_has_no_order_guarantee() {
        TableGeneratorDialect dialect = mock(TableGeneratorDialect.class);
        TableGeneratorBuilder b = new TableGeneratorBuilder(dialect);

        b.add(new Piece(10, "A"))
                .add(new Piece(10, "B"));

        String sql = b.build();
        assertTrue(sql.equals("AB") || sql.equals("BA"));
    }
}
