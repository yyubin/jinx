package org.jinx.migration.internal;

import org.jinx.migration.Dialect;
import org.jinx.migration.internal.drop.*;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DropContributorsTest {

    private static ColumnModel col(String name) {
        return ColumnModel.builder().columnName(name).build();
    }

    private static ConstraintModel cons(String name) {
        return ConstraintModel.builder().name(name).build();
    }

    private static IndexModel idx(String name) {
        return IndexModel.builder().indexName(name).build();
    }

    private static RelationshipModel rel(String col) {
        return RelationshipModel.builder().column(col).build();
    }

    private static TableGeneratorModel tg(String table) {
        return TableGeneratorModel.builder().table(table).build();
    }

    @Nested
    @DisplayName("ColumnDropContributor")
    class ColumnDropContributorTest {

        @Test
        void priority_and_delegate() {
            ColumnModel age = col("age");
            ColumnDropContributor c = new ColumnDropContributor("member", age);

            // priority
            assertThat(c.priority()).isEqualTo(20);

            // delegate
            Dialect d = mock(Dialect.class);
            when(d.getDropColumnSql("member", age)).thenReturn("--DROP COL--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropColumnSql("member", age);
            assertThat(sb).hasToString("--DROP COL--");
        }
    }

    /* ---------- ConstraintDropContributor ---------- */

    @Nested
    @DisplayName("ConstraintDropContributor")
    class ConstraintDropContributorTest {

        @Test
        void priority_and_delegate() {
            ConstraintModel ck = cons("ck_email");
            ConstraintDropContributor c = new ConstraintDropContributor("member", ck);

            assertThat(c.priority()).isEqualTo(30);

            Dialect d = mock(Dialect.class);
            when(d.getDropConstraintSql("member", ck)).thenReturn("--DROP CONS--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropConstraintSql("member", ck);
            assertThat(sb).hasToString("--DROP CONS--");
        }
    }

    @Nested
    @DisplayName("DropTableStatementContributor")
    class DropTableStatementContributorTest {

        @Test
        void priority_and_delegate() {
            DropTableStatementContributor c = new DropTableStatementContributor("member");

            assertThat(c.priority()).isEqualTo(10);

            Dialect d = mock(Dialect.class);
            when(d.getDropTableSql("member")).thenReturn("--DROP TABLE--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropTableSql("member");
            assertThat(sb).hasToString("--DROP TABLE--");
        }
    }

    @Nested
    @DisplayName("PrimaryKeyComplexDropContributor")
    class PrimaryKeyComplexDropContributorTest {

        @Test
        void priority_and_delegate() {
            List<ColumnModel> pkCols = List.of(col("id"));
            PrimaryKeyComplexDropContributor c =
                    new PrimaryKeyComplexDropContributor("member", pkCols);

            assertThat(c.priority()).isEqualTo(10);

            Dialect d = mock(Dialect.class);
            when(d.getDropPrimaryKeySql("member", pkCols)).thenReturn("--DROP PK COMPLEX--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropPrimaryKeySql("member", pkCols);
            assertThat(sb).hasToString("--DROP PK COMPLEX--");
        }
    }

    @Nested
    @DisplayName("PrimaryKeyDropContributor")
    class PrimaryKeyDropContributorTest {

        @Test
        void priority_and_delegate() {
            PrimaryKeyDropContributor c = new PrimaryKeyDropContributor("member");

            assertThat(c.priority()).isEqualTo(10);

            Dialect d = mock(Dialect.class);
            when(d.getDropPrimaryKeySql("member")).thenReturn("--DROP PK--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropPrimaryKeySql("member");
            assertThat(sb).hasToString("--DROP PK--");
        }
    }

    @Nested
    @DisplayName("IndexDropContributor")
    class IndexDropContributorTest {

        @Test
        void priority_and_delegate() {
            IndexModel idx = idx("idx_email");
            IndexDropContributor c = new IndexDropContributor("member", idx);

            assertThat(c.priority()).isEqualTo(30);

            Dialect d = mock(Dialect.class);
            when(d.getDropIndexSql("member", idx)).thenReturn("--DROP IDX--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropIndexSql("member", idx);
            assertThat(sb).hasToString("--DROP IDX--");
        }
    }

    @Nested
    @DisplayName("RelationshipDropContributor")
    class RelationshipDropContributorTest {

        @Test
        void priority_and_delegate() {
            RelationshipModel r = rel("order_id");
            RelationshipDropContributor c = new RelationshipDropContributor("order_item", r);

            assertThat(c.priority()).isEqualTo(30);

            Dialect d = mock(Dialect.class);
            when(d.getDropRelationshipSql("order_item", r)).thenReturn("--DROP REL--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropRelationshipSql("order_item", r);
            assertThat(sb).hasToString("--DROP REL--");
        }
    }

    @Nested
    @DisplayName("TableGeneratorDropContributor")
    class TableGeneratorDropContributorTest {

        @Test
        void priority_and_delegate() {
            TableGeneratorModel tg = tg("seq_tbl");
            TableGeneratorDropContributor c = new TableGeneratorDropContributor(tg);

            assertThat(c.priority()).isZero();

            Dialect d = mock(Dialect.class);
            when(d.getDropTableGeneratorSql(tg)).thenReturn("--DROP TG--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getDropTableGeneratorSql(tg);
            assertThat(sb).hasToString("--DROP TG--");
        }
    }
}
