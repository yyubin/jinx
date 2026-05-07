package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.spi.dialect.SequenceDialect;
import org.jinx.model.SequenceModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSqlSequenceVisitorTest {

    @Test @DisplayName("visitAddedSequence → getCreateSequenceSql 호출, SQL에 포함")
    void visitAdded_callsCreate() {
        SequenceDialect dialect = mock(SequenceDialect.class);
        SequenceModel seq = mock(SequenceModel.class);
        when(dialect.getCreateSequenceSql(seq)).thenReturn("CREATE SEQUENCE \"s\";\n");

        PostgreSqlSequenceVisitor v = new PostgreSqlSequenceVisitor(dialect);
        v.visitAddedSequence(seq);

        assertTrue(v.getGeneratedSql().contains("CREATE SEQUENCE \"s\""));
        verify(dialect).getCreateSequenceSql(seq);
    }

    @Test @DisplayName("visitDroppedSequence → getDropSequenceSql 호출, SQL에 포함")
    void visitDropped_callsDrop() {
        SequenceDialect dialect = mock(SequenceDialect.class);
        SequenceModel seq = mock(SequenceModel.class);
        when(dialect.getDropSequenceSql(seq)).thenReturn("DROP SEQUENCE IF EXISTS \"s\";\n");

        PostgreSqlSequenceVisitor v = new PostgreSqlSequenceVisitor(dialect);
        v.visitDroppedSequence(seq);

        assertTrue(v.getGeneratedSql().contains("DROP SEQUENCE IF EXISTS"));
    }

    @Test @DisplayName("visitModifiedSequence → getAlterSequenceSql 호출 (빈 문자열이면 SQL에 추가 안 됨)")
    void visitModified_callsAlter() {
        SequenceDialect dialect = mock(SequenceDialect.class);
        SequenceModel newSeq = mock(SequenceModel.class), oldSeq = mock(SequenceModel.class);
        when(dialect.getAlterSequenceSql(newSeq, oldSeq)).thenReturn("ALTER SEQUENCE \"s\" INCREMENT BY 100;\n");

        PostgreSqlSequenceVisitor v = new PostgreSqlSequenceVisitor(dialect);
        v.visitModifiedSequence(newSeq, oldSeq);

        assertTrue(v.getGeneratedSql().contains("ALTER SEQUENCE"));
    }

    @Test @DisplayName("visitModifiedSequence — ALTER가 빈 문자열이면 getGeneratedSql에 포함 안 됨")
    void visitModified_blankAlter_notIncluded() {
        SequenceDialect dialect = mock(SequenceDialect.class);
        SequenceModel seq = mock(SequenceModel.class);
        when(dialect.getAlterSequenceSql(seq, seq)).thenReturn("   ");

        PostgreSqlSequenceVisitor v = new PostgreSqlSequenceVisitor(dialect);
        v.visitModifiedSequence(seq, seq);

        assertTrue(v.getGeneratedSql().isBlank(), "빈 ALTER는 결과에 포함되지 않아야 함");
    }

    @Test @DisplayName("visit 없으면 getGeneratedSql은 빈 문자열")
    void noVisit_emptyResult() {
        SequenceDialect dialect = mock(SequenceDialect.class);
        PostgreSqlSequenceVisitor v = new PostgreSqlSequenceVisitor(dialect);
        assertTrue(v.getGeneratedSql().isEmpty());
    }

    @Test @DisplayName("복수 방문 — 결과가 모두 포함")
    void multipleVisits_allIncluded() {
        SequenceDialect dialect = mock(SequenceDialect.class);
        SequenceModel s1 = mock(SequenceModel.class), s2 = mock(SequenceModel.class);
        when(dialect.getCreateSequenceSql(s1)).thenReturn("CREATE SEQUENCE \"s1\";\n");
        when(dialect.getDropSequenceSql(s2)).thenReturn("DROP SEQUENCE IF EXISTS \"s2\";\n");

        PostgreSqlSequenceVisitor v = new PostgreSqlSequenceVisitor(dialect);
        v.visitAddedSequence(s1);
        v.visitDroppedSequence(s2);

        String sql = v.getGeneratedSql();
        assertTrue(sql.contains("CREATE SEQUENCE \"s1\""));
        assertTrue(sql.contains("DROP SEQUENCE IF EXISTS \"s2\""));
    }
}
